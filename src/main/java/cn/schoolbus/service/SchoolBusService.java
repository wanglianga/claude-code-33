package cn.schoolbus.service;

import cn.schoolbus.domain.*;
import cn.schoolbus.store.RedisStore;
import cn.schoolbus.support.ApiException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 校车点名与临时改乘核心业务：
 * 家长申请（容量→座位→校规→安全员确认）、早晨站点点名、放学名单生成、
 * 异常五方联动、通知回执、事件关闭归档、高发站点复盘。
 */
@Service
public class SchoolBusService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final RedisStore store;
    private final NotificationService notif;

    public SchoolBusService(RedisStore store, NotificationService notif) {
        this.store = store;
        this.notif = notif;
    }

    private String now() {
        return LocalDateTime.now().format(TS);
    }

    private String today() {
        return LocalDate.now().toString();
    }

    // ================= 登录 =================

    public User login(String username, String password) {
        User u = store.getUser(username == null ? "" : username.trim());
        if (u == null || !Objects.equals(u.password(), password)) {
            throw new ApiException("用户名或密码错误");
        }
        return u;
    }

    // ================= 基础查询 =================

    public List<Student> students() {
        List<Student> list = store.listStudents();
        list.sort(Comparator.comparing(Student::studentNo));
        return list;
    }

    public List<Route> routes() {
        List<Route> list = store.listRoutes();
        list.sort(Comparator.comparing(Route::id));
        return list;
    }

    public List<Vehicle> vehicles() {
        List<Vehicle> list = store.listVehicles();
        list.sort(Comparator.comparing(Vehicle::id));
        return list;
    }

    public RideStatus rideOf(String studentId) {
        RideStatus r = store.getRide(today(), studentId);
        if (r == null) {
            Student s = mustStudent(studentId);
            r = new RideStatus(s.id() + ":" + today(), today(), s.id(), s.classId(), s.routeId(),
                    s.defaultStop(), "PLANNED", "PLANNED", "NONE", null, null, null, null,
                    false, "", null, null, null, null, null, null, now(), 1);
            store.saveRide(r);
        }
        return r;
    }

    private Student mustStudent(String studentId) {
        Student s = store.getStudent(studentId);
        if (s == null) throw new ApiException("学生不存在: " + studentId);
        return s;
    }

    private Route mustRoute(String routeId) {
        Route r = store.getRoute(routeId);
        if (r == null) throw new ApiException("线路不存在: " + routeId);
        return r;
    }

    private Vehicle vehicleOfRoute(String routeId) {
        return store.listVehicles().stream()
                .filter(v -> routeId.equals(v.routeId())).findFirst().orElse(null);
    }

    private String teacherOfClass(String classId) {
        return store.listUsers().stream()
                .filter(u -> "TEACHER".equals(u.role()) && u.scopeIds().contains(classId))
                .map(User::username).findFirst().orElse(null);
    }

    private List<String> admins() {
        return store.listUsers().stream().filter(u -> "ADMIN".equals(u.role()))
                .map(User::username).toList();
    }

    /** 一个学生事件涉及的五方：家长、班主任、司机、随车安全员、校车管理员 */
    private LinkedHashSet<String> partiesOf(Student s, String routeId) {
        LinkedHashSet<String> p = new LinkedHashSet<>();
        if (s.parentUsername() != null) p.add(s.parentUsername());
        String teacher = teacherOfClass(s.classId());
        if (teacher != null) p.add(teacher);
        Vehicle v = vehicleOfRoute(routeId);
        if (v != null) {
            p.add(v.driverUsername());
            p.add(v.attendantUsername());
        }
        p.addAll(admins());
        return p;
    }

    /** 改乘学生的有效乘车线路（已批准改乘后走目标线路） */
    private String effectiveRoute(RideStatus r) {
        return "CHANGE_BUS".equals(r.changeType()) && r.targetRouteId() != null
                ? r.targetRouteId() : r.routeId();
    }

    private String effectiveStop(RideStatus r, boolean afternoon) {
        if ("CHANGE_BUS".equals(r.changeType()) && r.targetStop() != null) return r.targetStop();
        if (afternoon && "CHANGE_STOP".equals(r.changeType()) && r.targetStop() != null) return r.targetStop();
        return r.plannedStop();
    }

    private boolean ridingToday(RideStatus r) {
        return !"LEAVE".equals(r.changeType()) && !"LEAVE".equals(r.morningStatus());
    }

    /** 某线路今天的计划乘车人数（批准改乘计入目标线路，请假不计） */
    private long plannedCount(String routeId) {
        return store.listRides(today()).stream()
                .filter(this::ridingToday)
                .filter(r -> effectiveRoute(r).equals(routeId))
                .count();
    }

    // ================= 家长申请 =================

    public ChangeRequest submitRequest(User parent, Map<String, String> body) {
        String studentId = body.getOrDefault("studentId", "").trim();
        String type = body.getOrDefault("type", "").trim();
        Student s = mustStudent(studentId);
        if ("PARENT".equals(parent.role()) && !parent.scopeIds().contains(studentId)) {
            throw new ApiException("只能为本人监护的学生提交申请");
        }
        if (!List.of("LEAVE", "CHANGE_BUS", "CHANGE_STOP", "ALTERNATE_PICKUP").contains(type)) {
            throw new ApiException("申请类型非法");
        }

        String targetRouteId = body.getOrDefault("targetRouteId", "").trim();
        String targetStop = body.getOrDefault("targetStop", "").trim();
        String altPerson = body.getOrDefault("alternatePickupPerson", "").trim();
        String reason = body.getOrDefault("reason", "").trim();

        return store.withLock(() -> {
            RideStatus ride = rideOf(studentId);
            if ("LEAVE".equals(ride.changeType())) {
                throw new ApiException("该生今天已请假，不能再提交乘车变更");
            }

            // ---- 校规校验 ----
            String ruleCheck;
            String targetForCapacity = ride.routeId();
            switch (type) {
                case "LEAVE" -> {
                    if (reason.isBlank()) throw new ApiException("校规：请假必须填写原因");
                    ruleCheck = "校规通过：请假原因已登记，乘车名额将释放";
                }
                case "CHANGE_BUS" -> {
                    if (targetRouteId.isBlank() || targetStop.isBlank()) {
                        throw new ApiException("改乘必须选择目标线路与上/下车站点");
                    }
                    Route tr = mustRoute(targetRouteId);
                    if (!tr.stops().contains(targetStop)) {
                        throw new ApiException("校规：目标站点不在 " + tr.name() + " 站点表内");
                    }
                    targetForCapacity = targetRouteId;
                    ruleCheck = s.specialCare()
                            ? "校规通过（重点）：特殊照护学生改乘，已要求班主任与目标车安全员当面交接"
                            : "校规通过：临时改乘当日有效，次日自动恢复默认线路";
                }
                case "CHANGE_STOP" -> {
                    if (targetStop.isBlank()) throw new ApiException("必须填写变更后的下车点");
                    if (!mustRoute(ride.routeId()).stops().contains(targetStop)) {
                        throw new ApiException("校规：下车点必须是本线路已备案站点");
                    }
                    ruleCheck = "校规通过：新下车点在本线路备案站点内";
                }
                default -> {
                    if (altPerson.isBlank()) throw new ApiException("代接申请必须登记代接人姓名");
                    if (!reason.matches(".*\\d{11}.*")) {
                        throw new ApiException("校规：代接人须预留 11 位联系电话（写在说明里）");
                    }
                    ruleCheck = "校规通过：代接人姓名与联系电话已登记，下车时安全员核对";
                }
            }

            // ---- 线路容量 & 车辆座位（改乘才需要，申请学生本人不计入目标线路既有负载） ----
            String capacityCheck = "不涉及容量校验";
            String seatCheck = "不涉及座位校验";
            boolean capacityOk = true, seatOk = true;
            if ("CHANGE_BUS".equals(type)) {
                Route tr = mustRoute(targetRouteId);
                Vehicle tv = vehicleOfRoute(targetRouteId);
                long aboard = plannedCount(targetRouteId); // 当前不含申请人（申请人还在原线路）
                capacityOk = aboard + 1 <= tr.capacity();
                capacityCheck = (capacityOk ? "容量通过：" : "容量不足：")
                        + tr.name() + " 容量 " + tr.capacity() + " 人，当前计划 " + aboard
                        + " 人，加入后 " + (aboard + 1) + " 人";
                int seats = tv == null ? 0 : tv.seats();
                seatOk = aboard + 1 <= seats;
                seatCheck = (seatOk ? "座位通过：" : "座位不足：")
                        + (tv == null ? "目标线路无值班车辆" : tv.plate() + " 共 " + seats + " 座")
                        + "，加入后需 " + (aboard + 1) + " 座";
            }

            long id = store.nextId("request");
            ChangeRequest cr = new ChangeRequest(id, now(), studentId, parent.username(), today(), type,
                    targetRouteId, targetStop, altPerson, reason, "PENDING",
                    capacityCheck, seatCheck, ruleCheck, null, null, null);

            // 硬性校验不通过：系统直接驳回，不再占用安全员确认环节
            if (!capacityOk || !seatOk) {
                cr = new ChangeRequest(id, cr.createdAt(), studentId, parent.username(), today(), type,
                        targetRouteId, targetStop, altPerson, reason, "REJECTED",
                        capacityCheck, seatCheck, ruleCheck, "SYSTEM", now(),
                        !capacityOk ? "线路容量不足，系统自动驳回" : "车辆座位不足，系统自动驳回");
                store.saveRequest(cr);
                notif.broadcast("REQ:" + id + ":REJECT", null,
                        List.of(parent.username()), "申请未通过：" + typeName(type),
                        "学生 " + s.name() + " 的" + typeName(type) + "申请因"
                                + (!capacityOk ? "线路容量不足" : "车辆座位不足") + "未通过。");
                return cr;
            }

            store.saveRequest(cr);

            // 通知目标线路安全员/司机 + 班主任 + 管理员，等待安全员确认
            LinkedHashSet<String> targets = partiesOf(s, targetForCapacity);
            notif.broadcast("REQ:" + id + ":SUBMIT", null, targets,
                    "待确认：" + s.name() + " " + typeName(type) + "申请",
                    "家长提交【" + typeName(type) + "】" + requestDigest(cr)
                            + "。请随车安全员核对容量、座位与校规后确认。");
            return cr;
        });
    }

    public ChangeRequest reviewRequest(User reviewer, long id, boolean approve, String note) {
        if (!List.of("ATTENDANT", "ADMIN").contains(reviewer.role())) {
            throw new ApiException("仅随车安全员或校车管理员可以确认改乘申请");
        }
        return store.withLock(() -> {
            ChangeRequest cr = store.getRequest(id);
            if (cr == null) throw new ApiException("申请不存在");
            if (!"PENDING".equals(cr.status())) throw new ApiException("该申请已处理，不能重复确认");
            Student s = mustStudent(cr.studentId());

            String status = approve ? "APPROVED" : "REJECTED";
            ChangeRequest done = new ChangeRequest(cr.id(), cr.createdAt(), cr.studentId(),
                    cr.parentUsername(), cr.date(), cr.type(), cr.targetRouteId(), cr.targetStop(),
                    cr.alternatePickupPerson(), cr.reason(), status,
                    cr.capacityCheck(), cr.seatCheck(), cr.ruleCheck(),
                    reviewer.username(), now(), note);
            store.saveRequest(done);

            if (approve) {
                applyApprovedRequest(s, done);
            }

            LinkedHashSet<String> parties = partiesOf(s,
                    cr.targetRouteId().isBlank() ? s.routeId() : cr.targetRouteId());
            notif.broadcast("REQ:" + id + ":" + status, null, parties,
                    (approve ? "申请已通过：" : "申请已驳回：") + typeName(cr.type()),
                    "学生 " + s.name() + " 的" + typeName(cr.type()) + "申请已被"
                            + reviewer.name() + (approve ? "确认通过" : "驳回")
                            + (note == null || note.isBlank() ? "" : "，备注：" + note));
            return done;
        });
    }

    private void applyApprovedRequest(Student s, ChangeRequest cr) {
        RideStatus r = rideOf(s.id());
        RideStatus u;
        switch (cr.type()) {
            case "LEAVE" -> u = new RideStatus(r.id(), r.date(), r.studentId(), r.classId(), r.routeId(),
                    r.plannedStop(), "LEAVE", "LEAVE", "LEAVE", cr.id(), r.targetRouteId(),
                    r.targetStop(), r.alternatePickupPerson(), r.clubActivity(), r.parentNote(),
                    r.boardedBusId(), r.morningMarkedBy(), r.morningMarkedAt(),
                    r.afternoonMarkedBy(), r.afternoonMarkedAt(), r.parentConfirmed(), now(), r.version() + 1);
            case "CHANGE_BUS" -> u = new RideStatus(r.id(), r.date(), r.studentId(), r.classId(), r.routeId(),
                    r.plannedStop(), r.morningStatus(), r.afternoonStatus(), "CHANGE_BUS", cr.id(),
                    cr.targetRouteId(), cr.targetStop(), r.alternatePickupPerson(), r.clubActivity(),
                    r.parentNote(), r.boardedBusId(), r.morningMarkedBy(), r.morningMarkedAt(),
                    r.afternoonMarkedBy(), r.afternoonMarkedAt(), r.parentConfirmed(), now(), r.version() + 1);
            case "CHANGE_STOP" -> u = new RideStatus(r.id(), r.date(), r.studentId(), r.classId(), r.routeId(),
                    r.plannedStop(), r.morningStatus(), r.afternoonStatus(), "CHANGE_STOP", cr.id(),
                    r.targetRouteId(), cr.targetStop(), r.alternatePickupPerson(), r.clubActivity(),
                    r.parentNote(), r.boardedBusId(), r.morningMarkedBy(), r.morningMarkedAt(),
                    r.afternoonMarkedBy(), r.afternoonMarkedAt(), r.parentConfirmed(), now(), r.version() + 1);
            default -> u = new RideStatus(r.id(), r.date(), r.studentId(), r.classId(), r.routeId(),
                    r.plannedStop(), r.morningStatus(), r.afternoonStatus(), "ALTERNATE_PICKUP", cr.id(),
                    r.targetRouteId(), r.targetStop(), cr.alternatePickupPerson(), r.clubActivity(),
                    r.parentNote(), r.boardedBusId(), r.morningMarkedBy(), r.morningMarkedAt(),
                    r.afternoonMarkedBy(), r.afternoonMarkedAt(), r.parentConfirmed(), now(), r.version() + 1);
        }
        store.saveRide(u);
    }

    public List<ChangeRequest> requests() {
        return store.listRequests();
    }

    private String requestDigest(ChangeRequest cr) {
        return switch (cr.type()) {
            case "LEAVE" -> "（原因：" + cr.reason() + "）";
            case "CHANGE_BUS" -> "（改乘 " + mustRoute(cr.targetRouteId()).name()
                    + " / " + cr.targetStop() + "）";
            case "CHANGE_STOP" -> "（改在 " + cr.targetStop() + " 下车）";
            default -> "（代接人：" + cr.alternatePickupPerson() + "，电话见说明）";
        };
    }

    private String typeName(String t) {
        return switch (t) {
            case "LEAVE" -> "请假";
            case "CHANGE_BUS" -> "临时改乘";
            case "CHANGE_STOP" -> "改下车点";
            case "ALTERNATE_PICKUP" -> "他人代接";
            default -> t;
        };
    }

    // ================= 家长备注 / 社团 =================

    public RideStatus parentNote(User user, String studentId, String note) {
        Student s = mustStudent(studentId);
        if ("PARENT".equals(user.role()) && !user.scopeIds().contains(studentId)) {
            throw new ApiException("只能为本人监护的学生留言");
        }
        return store.withLock(() -> {
            RideStatus r = rideOf(studentId);
            RideStatus u = new RideStatus(r.id(), r.date(), r.studentId(), r.classId(), r.routeId(),
                    r.plannedStop(), r.morningStatus(), r.afternoonStatus(), r.changeType(),
                    r.changeRequestId(), r.targetRouteId(), r.targetStop(), r.alternatePickupPerson(),
                    r.clubActivity(), note, r.boardedBusId(), r.morningMarkedBy(), r.morningMarkedAt(),
                    r.afternoonMarkedBy(), r.afternoonMarkedAt(), r.parentConfirmed(), now(), r.version() + 1);
            store.saveRide(u);
            Vehicle v = vehicleOfRoute(effectiveRoute(r));
            Set<String> parties = new LinkedHashSet<>();
            if (v != null) { parties.add(v.attendantUsername()); parties.add(v.driverUsername()); }
            String t = teacherOfClass(s.classId());
            if (t != null) parties.add(t);
            notif.broadcast("NOTE:" + studentId, null, parties,
                    "家长备注：" + s.name(), (note == null || note.isBlank() ? "家长清空了备注" : "家长留言：" + note));
            return u;
        });
    }

    public RideStatus setClub(User user, String studentId, boolean club) {
        if ("PARENT".equals(user.role())) {
            throw new ApiException("社团参加情况由班主任登记");
        }
        Student s = mustStudent(studentId);
        return store.withLock(() -> {
            RideStatus r = rideOf(studentId);
            RideStatus u = new RideStatus(r.id(), r.date(), r.studentId(), r.classId(), r.routeId(),
                    r.plannedStop(), r.morningStatus(), r.afternoonStatus(), r.changeType(),
                    r.changeRequestId(), r.targetRouteId(), r.targetStop(), r.alternatePickupPerson(),
                    club, r.parentNote(), r.boardedBusId(), r.morningMarkedBy(), r.morningMarkedAt(),
                    r.afternoonMarkedBy(), r.afternoonMarkedAt(), r.parentConfirmed(), now(), r.version() + 1);
            store.saveRide(u);
            Vehicle v = vehicleOfRoute(effectiveRoute(r));
            Set<String> parties = new LinkedHashSet<>();
            if (v != null) { parties.add(v.attendantUsername()); parties.add(v.driverUsername()); }
            if (s.parentUsername() != null) parties.add(s.parentUsername());
            notif.broadcast("CLUB:" + studentId, null, parties,
                    "放学名单调整：" + s.name(),
                    s.name() + (club ? " 今天参加社团活动，不乘下午校车" : " 社团活动取消，恢复乘下午校车")
                            + "，放学乘车名单已同步更新。");
            return u;
        });
    }

    // ================= 早晨站点点名 =================

    public List<Map<String, Object>> morningRoster(String routeId) {
        mustRoute(routeId);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RideStatus r : store.listRides(today())) {
            if (!ridingToday(r) || !effectiveRoute(r).equals(routeId)) continue;
            Student s = mustStudent(r.studentId());
            Map<String, Object> row = studentRow(s, r);
            row.put("stop", effectiveStop(r, false));
            rows.add(row);
        }
        rows.sort(Comparator.comparing(m -> String.valueOf(m.get("stop"))));
        return rows;
    }

    public Map<String, Object> markMorning(User attendant, String studentId, String status) {
        if (!List.of("BOARDED", "ABSENT_NO_SHOW", "LATE", "TEMP_BOARDED").contains(status)) {
            throw new ApiException("点名状态非法");
        }
        Student s = mustStudent(studentId);
        return store.withLock(() -> {
            RideStatus r = rideOf(studentId);
            if (!ridingToday(r)) throw new ApiException("该生今天请假，不参与点名");
            // 已存在处理中的"未上车"事件时，禁止重复创建（先于状态写入校验，保证同一份事实一致）
            Incident existing = openStudentIncident("NOT_BOARDED", studentId);
            if ("ABSENT_NO_SHOW".equals(status) && existing != null) {
                throw new ApiException("已存在处理中的「未上车」异常事件 #" + existing.id()
                        + "，请在原事件上更新进展");
            }
            String routeId = effectiveRoute(r);
            String busId = Optional.ofNullable(vehicleOfRoute(routeId)).map(Vehicle::id).orElse(null);
            RideStatus u = new RideStatus(r.id(), r.date(), r.studentId(), r.classId(), r.routeId(),
                    r.plannedStop(), status, r.afternoonStatus(), r.changeType(), r.changeRequestId(),
                    r.targetRouteId(), r.targetStop(), r.alternatePickupPerson(), r.clubActivity(),
                    r.parentNote(), busId, attendant.username(), now(),
                    r.afternoonMarkedBy(), r.afternoonMarkedAt(), r.parentConfirmed(), now(), r.version() + 1);
            store.saveRide(u);

            Incident inc = null;
            if ("ABSENT_NO_SHOW".equals(status)) {
                inc = openIncident("NOT_BOARDED", s, routeId, effectiveStop(r, false), attendant,
                        "早晨 " + effectiveStop(r, false) + " 站点点名未到"
                                + (s.specialCare() ? "（特殊照护学生，已升级处理）" : ""), null);
            } else {
                // 学生赶到后补登（迟到/临时上车/已上车）：进展回写到已开启的"未上车"事件
                if (existing != null) {
                    appendTimeline(existing, attendant,
                            "学生已赶到站点，点名状态补登为「" + morningName(status) + "」");
                    notif.broadcast("MORNING:UPDATE:" + existing.id(), existing.id(),
                            partiesOfIncident(existing), "事件进展 #" + existing.id() + "：学生已赶到",
                            s.name() + " 已到 " + effectiveStop(r, false) + " 站，状态补登为「"
                                    + morningName(status) + "」（" + attendant.name() + "）");
                }
                // 状态同步给班主任和家长
                LinkedHashSet<String> parties = partiesOf(s, routeId);
                notif.broadcast("MORNING:" + studentId + ":" + status, null, parties,
                        "早晨点名：" + s.name() + " " + morningName(status),
                        s.name() + " 在 " + effectiveStop(r, false) + " 站状态：" + morningName(status)
                                + "（点名：" + attendant.name() + "）"
                                + (r.parentNote().isBlank() ? "" : "｜家长备注：" + r.parentNote()));
            }
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("ride", store.getRide(today(), studentId));
            resp.put("incident", inc);
            return resp;
        });
    }

    /** 发现学生上错车（站在非本人线路车辆上）；若学生已赶到并补登其他点名状态，在原事件更新 */
    public Incident markWrongBus(User actor, String studentId, String vehicleId) {
        Student s = mustStudent(studentId);
        Vehicle v = store.getVehicle(vehicleId);
        if (v == null) throw new ApiException("车辆不存在");
        return store.withLock(() -> {
            Incident existing = openStudentIncident("WRONG_BUS", studentId);
            if (existing != null) {
                throw new ApiException("已存在处理中的「上错车」异常事件 #" + existing.id()
                        + "，请在原事件上更新进展");
            }
            return openIncident("WRONG_BUS", s, v.routeId(),
                    "车上（" + v.plate() + "）", actor,
                    s.name() + " 上错车，当前在 " + v.plate() + "（" + mustRoute(v.routeId()).name() + "）", null);
        });
    }

    // ================= 车辆实时状态 =================

    public Vehicle updateVehicle(User driver, String vehicleId, String status,
                                 String locationText, Integer delayMinutes) {
        if (!List.of("ON_TIME", "DELAYED", "RUNNING", "FINISHED").contains(status)) {
            throw new ApiException("车辆状态非法");
        }
        Vehicle v = store.getVehicle(vehicleId);
        if (v == null) throw new ApiException("车辆不存在");
        if ("DRIVER".equals(driver.role()) && !driver.scopeIds().contains(vehicleId)) {
            throw new ApiException("只能更新本人驾驶的车辆");
        }
        return store.withLock(() -> {
            int delay = delayMinutes == null ? v.delayMinutes() : delayMinutes;
            Vehicle u = new Vehicle(v.id(), v.plate(), v.seats(), v.routeId(), v.driverUsername(),
                    v.attendantUsername(), status, locationText == null ? v.locationText() : locationText,
                    "DELAYED".equals(status) ? delay : 0, now());
            store.saveVehicle(u);

            // 找今天该线路是否已有打开的延误事件
            Incident open = store.listIncidents().stream()
                    .filter(i -> "BUS_DELAY".equals(i.type()) && "OPEN".equals(i.status())
                            && vehicleId.equals(i.vehicleId()) && today().equals(i.date()))
                    .findFirst().orElse(null);

            if ("DELAYED".equals(status)) {
                String desc = v.plate() + "（" + mustRoute(v.routeId()).name() + "）延误约 "
                        + delay + " 分钟，最新位置：" + u.locationText();
                if (open == null) {
                    // 线路级事件：把该线路所有乘车学生的家长、班主任、司乘、管理员串到同一事件
                    List<String> riderParents = new ArrayList<>();
                    Set<String> teachers = new LinkedHashSet<>();
                    for (RideStatus r : store.listRides(today())) {
                        if (ridingToday(r) && effectiveRoute(r).equals(v.routeId())) {
                            Student rs = mustStudent(r.studentId());
                            if (rs.parentUsername() != null) riderParents.add(rs.parentUsername());
                            String t = teacherOfClass(rs.classId());
                            if (t != null) teachers.add(t);
                        }
                    }
                    LinkedHashSet<String> parties = new LinkedHashSet<>();
                    parties.add(v.driverUsername());
                    parties.add(v.attendantUsername());
                    parties.addAll(teachers);
                    parties.addAll(riderParents);
                    parties.addAll(admins());
                    open = openIncidentRaw("BUS_DELAY", null, v.routeId(), vehicleId,
                            u.locationText(), driver, desc, parties);
                } else {
                    appendTimeline(open, driver, "位置更新：" + u.locationText() + "，延误约 " + delay + " 分钟");
                    notif.broadcast("DELAY:UPDATE:" + open.id(), open.id(), partiesOfIncident(open),
                            "延误更新：" + v.plate(), desc);
                }
            } else if (open != null && "ON_TIME".equals(status)) {
                appendTimeline(open, driver, "车辆已恢复准点运行，位置：" + u.locationText());
                notif.broadcast("DELAY:RECOVER:" + open.id(), open.id(), partiesOfIncident(open),
                        "恢复准点：" + v.plate(), v.plate() + " 已恢复准点，位置：" + u.locationText());
            }
            return u;
        });
    }

    // ================= 放学乘车名单 =================

    public Map<String, Object> afternoonRoster(String routeId) {
        mustRoute(routeId);
        List<Map<String, Object>> riders = new ArrayList<>();
        List<Map<String, Object>> excluded = new ArrayList<>();
        for (RideStatus r : store.listRides(today())) {
            Student s = mustStudent(r.studentId());
            Map<String, Object> row = studentRow(s, r);
            if ("LEAVE".equals(r.changeType())) {
                if (s.routeId().equals(routeId)) {
                    row.put("excludeReason", "请假");
                    excluded.add(row);
                }
                continue;
            }
            if (!effectiveRoute(r).equals(routeId)) continue;
            row.put("stop", effectiveStop(r, true));
            if (r.clubActivity()) {
                row.put("excludeReason", "参加社团活动");
                excluded.add(row);
                continue;
            }
            row.put("pickupPerson", "ALTERNATE_PICKUP".equals(r.changeType())
                    ? r.alternatePickupPerson() + "（临时代接）" : s.pickupPerson());
            riders.add(row);
        }
        // 按线路站点顺序排序
        Route route = mustRoute(routeId);
        riders.sort(Comparator.comparingInt(m -> {
            int i = route.stops().indexOf(String.valueOf(m.get("stop")));
            return i < 0 ? 999 : i;
        }));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("route", route);
        resp.put("vehicle", vehicleOfRoute(routeId));
        resp.put("riders", riders);
        resp.put("excluded", excluded);
        return resp;
    }

    public Map<String, Object> markAfternoon(User actor, String studentId, String status) {
        if (!List.of("ONBOARD", "DELIVERED", "NOT_BOARDED", "NO_PICKUP", "WRONG_BUS").contains(status)) {
            throw new ApiException("放学点名状态非法");
        }
        Student s = mustStudent(studentId);
        return store.withLock(() -> {
            RideStatus r = rideOf(studentId);
            if (!ridingToday(r)) throw new ApiException("该生今天请假");
            if (List.of("NO_PICKUP", "NOT_BOARDED", "WRONG_BUS").contains(status)) {
                Incident existing = openStudentIncident(status, studentId);
                if (existing != null) {
                    throw new ApiException("已存在处理中的「" + incidentName(status)
                            + "」异常事件 #" + existing.id() + "，请在原事件上更新进展");
                }
            }
            String routeId = effectiveRoute(r);
            String busId = Optional.ofNullable(vehicleOfRoute(routeId)).map(Vehicle::id).orElse(r.boardedBusId());
            RideStatus u = new RideStatus(r.id(), r.date(), r.studentId(), r.classId(), r.routeId(),
                    r.plannedStop(), r.morningStatus(), status, r.changeType(), r.changeRequestId(),
                    r.targetRouteId(), r.targetStop(), r.alternatePickupPerson(), r.clubActivity(),
                    r.parentNote(), busId, r.morningMarkedBy(), r.morningMarkedAt(),
                    actor.username(), now(), r.parentConfirmed(), now(), r.version() + 1);
            store.saveRide(u);

            Incident inc = null;
            String stop = effectiveStop(r, true);
            if ("NO_PICKUP".equals(status) || "NOT_BOARDED".equals(status) || "WRONG_BUS".equals(status)) {
                String desc = switch (status) {
                    case "NO_PICKUP" -> "放学送达 " + stop + "，约定接娃人（"
                            + ("ALTERNATE_PICKUP".equals(r.changeType()) ? r.alternatePickupPerson() : s.pickupPerson())
                            + "）未出现，安全员现场看护中";
                    case "NOT_BOARDED" -> "放学点名时该生未上车";
                    default -> "该生被发现上错车";
                };
                inc = openIncident(status, s, routeId, stop, actor, desc, null);
            } else {
                LinkedHashSet<String> parties = partiesOf(s, routeId);
                notif.broadcast("AFTERNOON:" + studentId + ":" + status, null, parties,
                        "放学点名：" + s.name() + " " + afternoonName(status),
                        s.name() + " 状态：" + afternoonName(status) + "（" + actor.name() + "）");
            }
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("ride", store.getRide(today(), studentId));
            resp.put("incident", inc);
            return resp;
        });
    }

    /** 家长手动登记临时变更接送人（先变更、后串联五方的场景） */
    public Incident reportPickupChange(User actor, String studentId, String description) {
        Student s = mustStudent(studentId);
        return store.withLock(() -> {
            RideStatus r = rideOf(studentId);
            String routeId = effectiveRoute(r);
            return openIncident("PICKUP_CHANGE", s, routeId, effectiveStop(r, true), actor,
                    "家长临时变更接送人：" + description, null);
        });
    }

    /** 家长确认孩子已接到 */
    public RideStatus parentConfirm(String studentId) {
        Student s = mustStudent(studentId);
        return store.withLock(() -> {
            RideStatus r = rideOf(studentId);
            RideStatus u = new RideStatus(r.id(), r.date(), r.studentId(), r.classId(), r.routeId(),
                    r.plannedStop(), r.morningStatus(), r.afternoonStatus(), r.changeType(),
                    r.changeRequestId(), r.targetRouteId(), r.targetStop(), r.alternatePickupPerson(),
                    r.clubActivity(), r.parentNote(), r.boardedBusId(), r.morningMarkedBy(),
                    r.morningMarkedAt(), r.afternoonMarkedBy(), r.afternoonMarkedAt(), now(),
                    now(), r.version() + 1);
            store.saveRide(u);
            // 家长确认追加到该生今天所有未关闭事件
            for (Incident inc : store.listIncidents()) {
                if ("OPEN".equals(inc.status()) && s.id().equals(inc.studentId()) && today().equals(inc.date())) {
                    appendTimeline(inc, store.getUser(s.parentUsername()),
                            "家长确认：孩子已安全交到本人手中");
                    notif.broadcast("CONFIRM:" + inc.id(), inc.id(), partiesOfIncident(inc),
                            "家长已确认：" + s.name(), "家长已确认 " + s.name() + " 安全接到。");
                }
            }
            return u;
        });
    }

    // ================= 异常事件 =================

    public List<Incident> incidents(String status, String routeId) {
        return store.listIncidents().stream()
                .filter(i -> status == null || status.isBlank() || status.equals(i.status()))
                .filter(i -> routeId == null || routeId.isBlank() || routeId.equals(i.routeId()))
                .toList();
    }

    public Incident incident(long id) {
        Incident i = store.getIncident(id);
        if (i == null) throw new ApiException("事件不存在");
        return i;
    }

    public List<Notification> incidentReceipts(long incidentId) {
        incident(incidentId);
        return store.listNotificationsByIncident(incidentId);
    }

    public Incident appendAction(User actor, long incidentId, String action) {
        if (action == null || action.isBlank()) throw new ApiException("处理动作不能为空");
        return store.withLock(() -> {
            Incident inc = incident(incidentId);
            if ("RESOLVED".equals(inc.status())) throw new ApiException("事件已关闭，不能再更新");
            appendTimeline(inc, actor, action);
            notif.broadcast("ACTION:" + incidentId, incidentId, partiesOfIncident(inc),
                    "事件进展 #" + incidentId, actor.name() + "：" + action);
            return store.getIncident(incidentId);
        });
    }

    public ArchiveEntry resolve(User admin, long incidentId, String resolution, String responsibility) {
        if (!"ADMIN".equals(admin.role())) throw new ApiException("仅校车管理员可以关闭事件");
        if (resolution == null || resolution.isBlank()) throw new ApiException("必须填写处理结果");
        if (responsibility == null || responsibility.isBlank()) throw new ApiException("必须填写责任结论");
        return store.withLock(() -> {
            Incident inc = incident(incidentId);
            if ("RESOLVED".equals(inc.status())) throw new ApiException("事件已关闭");
            List<Incident.TimelineItem> tl = new ArrayList<>(inc.timeline());
            tl.add(new Incident.TimelineItem(now(), admin.username(), admin.role(),
                    "关闭事件｜处理结果：" + resolution + "｜责任结论：" + responsibility));
            Incident done = new Incident(inc.id(), inc.openedAt(), now(), inc.type(), inc.date(),
                    inc.studentId(), inc.routeId(), inc.vehicleId(), inc.stop(), inc.openedBy(),
                    inc.description(), "RESOLVED", tl, resolution, responsibility);
            store.saveIncident(done);

            // 归档：线路级事件（延误）按车上每名学生分别入档；学生级事件入该生档案
            List<String> studentIds;
            if (inc.studentId() != null && !inc.studentId().isBlank()) {
                studentIds = List.of(inc.studentId());
            } else {
                studentIds = store.listRides(inc.date()).stream()
                        .filter(this::ridingToday)
                        .filter(r -> effectiveRoute(r).equals(inc.routeId()))
                        .map(RideStatus::studentId).toList();
            }
            List<Long> notifIds = store.listNotificationsByIncident(incidentId).stream()
                    .map(Notification::id).toList();
            Vehicle v = inc.vehicleId() == null ? vehicleOfRoute(inc.routeId()) : store.getVehicle(inc.vehicleId());
            String vehicleSnap = v == null ? "" : v.plate() + " 状态:" + v.status()
                    + " 位置:" + v.locationText() + " 延误:" + v.delayMinutes() + "分 快照时间:" + v.updatedAt();

            ArchiveEntry first = null;
            for (String sid : studentIds) {
                RideStatus r = store.getRide(inc.date(), sid);
                ArchiveEntry a = new ArchiveEntry(
                        inc.id() + ":" + sid, now(), inc.date(), sid, inc.id(), inc.type(),
                        resolution, responsibility,
                        r == null ? "" : r.morningStatus(),
                        r == null ? "" : r.afternoonStatus(),
                        r == null ? null : r.parentConfirmed(),
                        vehicleSnap, notifIds);
                store.saveArchive(a);
                if (first == null) first = a;
            }

            notif.broadcast("RESOLVE:" + incidentId, incidentId, partiesOfIncident(inc),
                    "事件已关闭 #" + incidentId,
                    "处理结果：" + resolution + "｜责任结论：" + responsibility + "，已归入学生乘车档案。");
            return first;
        });
    }

    public List<ArchiveEntry> archives(String studentId) {
        if (studentId != null && !studentId.isBlank()) return store.listArchivesByStudent(studentId);
        return store.listArchives();
    }

    // ================= 复盘 / 统一事实 =================

    /** 异常高发站点复盘：按线路→站点统计异常次数与类型分布 */
    public Map<String, Object> hotspotReview(String routeId) {
        List<Incident> all = store.listIncidents().stream()
                .filter(i -> routeId == null || routeId.isBlank() || routeId.equals(i.routeId()))
                .toList();
        Map<String, Map<String, Object>> stopAgg = new TreeMap<>();
        for (Incident i : all) {
            // 车辆延误是线路级事件，其 stop 存的是定位快照而非站点，不计入站点排行
            if (i.stop() == null || "BUS_DELAY".equals(i.type())) continue;
            String key = i.routeId() + "|" + i.stop();
            Map<String, Object> agg = stopAgg.computeIfAbsent(key, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("routeId", i.routeId());
                m.put("stop", i.stop());
                m.put("total", 0);
                m.put("open", 0);
                m.put("byType", new LinkedHashMap<String, Integer>());
                return m;
            });
            agg.put("total", (int) agg.get("total") + 1);
            if ("OPEN".equals(i.status())) agg.put("open", (int) agg.get("open") + 1);
            @SuppressWarnings("unchecked")
            Map<String, Integer> byType = (Map<String, Integer>) agg.get("byType");
            byType.merge(incidentName(i.type()), 1, Integer::sum);
        }
        List<Map<String, Object>> hotspots = new ArrayList<>(stopAgg.values());
        hotspots.sort((a, b) -> Integer.compare((int) b.get("total"), (int) a.get("total")));

        // 回执总体到位率
        long totalNotif = store.listNotifications().stream()
                .filter(n -> n.incidentId() != null).count();
        long acked = store.listNotifications().stream()
                .filter(n -> n.incidentId() != null && n.ack()).count();

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("incidentTotal", all.size());
        resp.put("openTotal", all.stream().filter(i -> "OPEN".equals(i.status())).count());
        resp.put("receiptTotal", totalNotif);
        resp.put("receiptAcked", acked);
        resp.put("receiptRate", totalNotif == 0 ? 100 : Math.round(acked * 1000.0 / totalNotif) / 10.0);
        resp.put("hotspots", hotspots);
        return resp;
    }

    /** 全量当天乘车事实（学生状态 × 车辆实时状态），各角色看到的是同一份 */
    public Map<String, Object> dailyFacts() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RideStatus r : store.listRides(today())) {
            Student s = store.getStudent(r.studentId());
            if (s == null) continue;
            Map<String, Object> row = studentRow(s, r);
            String effRoute = effectiveRoute(r);
            row.put("effectiveRouteId", effRoute);
            Route rt = store.getRoute(effRoute);
            row.put("effectiveRouteName", rt == null ? null : rt.name());
            row.put("morningStop", effectiveStop(r, false));
            row.put("afternoonStop", effectiveStop(r, true));
            row.put("vehicle", vehicleOfRoute(effRoute));
            rows.add(row);
        }
        rows.sort(Comparator.comparing(m -> String.valueOf(m.get("studentNo"))));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("date", today());
        resp.put("vehicles", vehicles());
        resp.put("facts", rows);
        return resp;
    }

    // ================= 事件内部工具 =================

    private Incident openStudentIncident(String type, String studentId) {
        return store.listIncidents().stream()
                .filter(i -> "OPEN".equals(i.status()) && i.type().equals(type)
                        && i.date().equals(today()) && Objects.equals(i.studentId(), studentId))
                .findFirst().orElse(null);
    }

    private Incident openIncident(String type, Student s, String routeId, String stop,
                                  User opener, String description, Collection<String> extraParties) {
        LinkedHashSet<String> parties = partiesOf(s, routeId);
        if (extraParties != null) parties.addAll(extraParties);
        return openIncidentRaw(type, s.id(), routeId,
                Optional.ofNullable(vehicleOfRoute(routeId)).map(Vehicle::id).orElse(null),
                stop, opener, description, parties);
    }

    private Incident openIncidentRaw(String type, String studentId, String routeId, String vehicleId,
                                     String stop, User opener, String description, Collection<String> parties) {
        // 同类型同日防重（学生级）/ 同车同日防重（延误级）
        boolean dup = store.listIncidents().stream().anyMatch(i -> "OPEN".equals(i.status())
                && i.type().equals(type) && i.date().equals(today())
                && (type.equals("BUS_DELAY")
                    ? Objects.equals(i.vehicleId(), vehicleId)
                    : Objects.equals(i.studentId(), studentId)));
        if (dup) throw new ApiException("已存在处理中的同类异常事件，请勿重复创建，请在原事件上更新进展");

        long id = store.nextId("incident");
        List<Incident.TimelineItem> tl = new ArrayList<>();
        tl.add(new Incident.TimelineItem(now(), opener.username(), opener.role(),
                "创建事件：" + description));
        Incident inc = new Incident(id, now(), null, type, today(), studentId, routeId, vehicleId,
                stop, opener.username(), description, "OPEN", tl, null, null);
        store.saveIncident(inc);

        notif.broadcast("INC:" + id, id, parties,
                "乘车异常 #" + id + "：" + incidentName(type),
                description + "。司机、安全员、班主任、家长、管理员已同步到同一事件，请协同处置。");
        return inc;
    }

    private void appendTimeline(Incident inc, User actor, String action) {
        String actorName = actor == null ? "SYSTEM" : actor.username();
        String role = actor == null ? "SYSTEM" : actor.role();
        List<Incident.TimelineItem> tl = new ArrayList<>(inc.timeline());
        tl.add(new Incident.TimelineItem(now(), actorName, role, action));
        Incident u = new Incident(inc.id(), inc.openedAt(), inc.closedAt(), inc.type(), inc.date(),
                inc.studentId(), inc.routeId(), inc.vehicleId(), inc.stop(), inc.openedBy(),
                inc.description(), inc.status(), tl, inc.resolution(), inc.responsibility());
        store.saveIncident(u);
    }

    private Set<String> partiesOfIncident(Incident inc) {
        LinkedHashSet<String> parties = new LinkedHashSet<>();
        store.listNotificationsByIncident(inc.id()).stream()
                .map(Notification::recipientUsername).forEach(parties::add);
        return parties;
    }

    // ================= DTO 工具 =================

    private Map<String, Object> studentRow(Student s, RideStatus r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("studentId", s.id());
        m.put("studentNo", s.studentNo());
        m.put("studentName", s.name());
        m.put("classId", s.classId());
        m.put("className", s.className());
        m.put("routeId", s.routeId());
        m.put("defaultStop", s.defaultStop());
        m.put("pickupPerson", s.pickupPerson());
        m.put("parentPhone", s.parentPhone());
        m.put("parentUsername", s.parentUsername());
        m.put("specialCare", s.specialCare());
        m.put("studentNote", s.note());
        m.put("ride", r);
        return m;
    }

    public String incidentName(String t) {
        return switch (t) {
            case "NOT_BOARDED" -> "未上车";
            case "WRONG_BUS" -> "上错车";
            case "NO_PICKUP" -> "下车点无人接";
            case "BUS_DELAY" -> "车辆延误";
            case "PICKUP_CHANGE" -> "临时变更接送人";
            default -> t;
        };
    }

    private String morningName(String s) {
        return switch (s) {
            case "BOARDED" -> "已上车";
            case "ABSENT_NO_SHOW" -> "未到";
            case "LATE" -> "迟到";
            case "TEMP_BOARDED" -> "临时上车";
            case "LEAVE" -> "请假";
            default -> s;
        };
    }

    private String afternoonName(String s) {
        return switch (s) {
            case "ONBOARD" -> "已上车";
            case "DELIVERED" -> "已送达";
            case "NOT_BOARDED" -> "未上车";
            case "NO_PICKUP" -> "无人接（看护中）";
            case "WRONG_BUS" -> "上错车";
            default -> s;
        };
    }
}
