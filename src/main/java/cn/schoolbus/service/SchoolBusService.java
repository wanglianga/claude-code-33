package cn.schoolbus.service;

import cn.schoolbus.domain.*;
import cn.schoolbus.store.RedisStore;
import cn.schoolbus.support.ApiException;
import cn.schoolbus.support.ForbiddenException;
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

    // ================= 角色 / scope 守卫 =================

    private void requireRole(User u, String... roles) {
        for (String r : roles) {
            if (r.equals(u.role())) return;
        }
        throw new ForbiddenException("无权操作：需要 "
                + java.util.Arrays.stream(roles).map(this::roleName).reduce((a, b) -> a + "/" + b).orElse("")
                + " 身份");
    }

    private String roleName(String r) {
        return switch (r) {
            case "ADMIN" -> "校车管理员";
            case "DRIVER" -> "司机";
            case "ATTENDANT" -> "随车安全员";
            case "TEACHER" -> "班主任";
            case "PARENT" -> "家长";
            default -> r;
        };
    }

    /** 家长只能操作本人监护学生；其他角色不做学生归属限制（另有线路/班级 scope 校验） */
    private void requireParentChild(User u, Student s) {
        if ("PARENT".equals(u.role()) && !u.scopeIds().contains(s.id())) {
            throw new ForbiddenException("越权：只能操作本人监护的学生（" + s.name() + "）");
        }
    }

    /** 班主任只能操作授权班级的学生 */
    private void requireTeacherClass(User u, Student s) {
        if ("TEACHER".equals(u.role()) && !u.scopeIds().contains(s.classId())) {
            throw new ForbiddenException("越权：您不是 " + s.className() + " 的班主任");
        }
    }

    /**
     * 点名（早晨/放学）：仅随车安全员/管理员；安全员只能操作所属线路（学生当天有效线路），
     * 上错车场景必须是学生有效线路与当前车辆线路之一的安全员。
     */
    private void requireRosterAccess(User u, Student s, String routeId) {
        requireRole(u, "ATTENDANT", "ADMIN");
        if ("ADMIN".equals(u.role())) return;
        if (!u.scopeIds().contains(routeId)) {
            throw new ForbiddenException("越权：您不属于 " + mustRoute(routeId).name()
                    + " 的随车安全员，不能对该线路学生点名");
        }
    }

    /** 只有司机可以上报本车实时状态；管理员可代操作（调度） */
    private void requireVehicleAccess(User u, Vehicle v) {
        requireRole(u, "DRIVER", "ADMIN");
        if ("DRIVER".equals(u.role()) && !u.scopeIds().contains(v.id())) {
            throw new ForbiddenException("越权：只能更新本人驾驶的车辆（" + v.plate() + "）");
        }
    }

    /** 事件处置/催办：五方人员中与该事件相关的人，或管理员 */
    private void requireIncidentAccess(User u, Incident inc) {
        if ("ADMIN".equals(u.role())) return;
        if (inc.studentId() != null && !inc.studentId().isBlank()) {
            Student s = mustStudent(inc.studentId());
            requireParentChild(u, s);
            requireTeacherClass(u, s);
            if ("DRIVER".equals(u.role()) || "ATTENDANT".equals(u.role())) {
                Vehicle v = inc.vehicleId() == null ? null : store.getVehicle(inc.vehicleId());
                // 学生当前有效线路（上错车时学生本线与实际所在车辆线路可能不同，两线司乘均可访问）
                RideStatus cur = store.getRide(inc.date(), s.id());
                String studentRoute = cur == null ? s.routeId() : effectiveRoute(cur);
                Vehicle studentVehicle = vehicleOfRoute(studentRoute);
                if ("DRIVER".equals(u.role())) {
                    boolean drivesThis = v != null && u.username().equals(v.driverUsername());
                    boolean drivesHome = studentVehicle != null && u.username().equals(studentVehicle.driverUsername());
                    if (!drivesThis && !drivesHome) {
                        throw new ForbiddenException("越权：该事件不涉及您驾驶的车辆");
                    }
                } else {
                    String scopeRoute = v == null ? inc.routeId() : v.routeId();
                    if (!u.scopeIds().contains(scopeRoute) && !u.scopeIds().contains(studentRoute)) {
                        throw new ForbiddenException("越权：该事件不在您负责的线路");
                    }
                }
            }
            return;
        }
        // 线路级事件：该线路司机/安全员、该车在乘学生的班主任/家长
        Vehicle v = inc.vehicleId() == null ? vehicleOfRoute(inc.routeId()) : store.getVehicle(inc.vehicleId());
        switch (u.role()) {
            case "DRIVER" -> {
                if (v == null || !u.username().equals(v.driverUsername())) {
                    throw new ForbiddenException("越权：该线路级事件不涉及您驾驶的车辆");
                }
            }
            case "ATTENDANT" -> {
                if (!u.scopeIds().contains(inc.routeId())) {
                    throw new ForbiddenException("越权：该线路级事件不在您负责的线路");
                }
            }
            case "TEACHER", "PARENT" -> {
                boolean related = store.listRides(inc.date()).stream()
                        .filter(this::ridingToday)
                        .filter(r -> effectiveRoute(r).equals(inc.routeId()))
                        .map(RideStatus::studentId)
                        .anyMatch(sid -> {
                            Student rs = mustStudent(sid);
                            return "PARENT".equals(u.role())
                                    ? u.scopeIds().contains(sid)
                                    : u.scopeIds().contains(rs.classId());
                        });
                if (!related) {
                    throw new ForbiddenException("越权：该线路级事件与您的学生无关");
                }
            }
            default -> throw new ForbiddenException("无权操作该事件");
        }
    }

    // ================= 通知 / 基础查询 =================

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
        requireRole(parent, "PARENT", "ADMIN");
        String studentId = body.getOrDefault("studentId", "").trim();
        String type = body.getOrDefault("type", "").trim();
        Student s = mustStudent(studentId);
        requireParentChild(parent, s);
        if (!List.of("LEAVE", "CHANGE_BUS", "CHANGE_STOP", "ALTERNATE_PICKUP").contains(type)) {
            throw new ApiException("申请类型非法");
        }

        String targetRouteId = body.getOrDefault("targetRouteId", "").trim();
        String targetStop = body.getOrDefault("targetStop", "").trim();
        String altPerson = body.getOrDefault("alternatePickupPerson", "").trim();
        String reason = body.getOrDefault("reason", "").trim();
        boolean wantWaitlist = "true".equalsIgnoreCase(body.getOrDefault("waitlist", ""))
                || "1".equals(body.getOrDefault("waitlist", ""));

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

            // ---- 改乘四项校验：车辆座位 / 同站点人数 / 绕行时间 / 随车安全员确认 ----
            String capacityCheck = "不涉及容量校验";
            String seatCheck = "不涉及座位校验";
            String stopCheck = "不涉及同站点校验";
            String detourCheck = "不涉及绕行时间校验";
            BusChangeEval eval = null;
            if ("CHANGE_BUS".equals(type)) {
                eval = evaluateBusChange(ride.routeId(), targetRouteId, targetStop);
                capacityCheck = eval.capacityText;
                seatCheck = eval.seatText;
                stopCheck = eval.stopText;
                detourCheck = eval.detourText;
            }

            long id = store.nextId("request");
            ChangeRequest cr = new ChangeRequest(id, now(), studentId, parent.username(), today(), type,
                    targetRouteId, targetStop, altPerson, reason, "PENDING", false,
                    capacityCheck, seatCheck, stopCheck, detourCheck, ruleCheck,
                    null, null, null, null, null);

            // 容量/座位/同站点/绕行任一硬性校验不通过：家长可选择候补或放弃
            if (eval != null && !eval.hardOk) {
                // 绕行时间不随名额释放而改善，绕行超限只能放弃，不接受候补
                boolean canWaitlist = eval.capacityOk == false || eval.seatOk == false || eval.stopOk == false;
                if (wantWaitlist && eval.detourOk && canWaitlist) {
                    int ahead = (int) store.listRequests().stream()
                            .filter(x -> "WAITLIST".equals(x.status())
                                    && x.targetRouteId().equals(targetRouteId))
                            .count();
                    cr = copyRequest(cr, "WAITLIST", true, null, null, null, null, null);
                    store.saveRequest(cr);
                    LinkedHashSet<String> admins = new LinkedHashSet<>(admins());
                    Vehicle tv = vehicleOfRoute(targetRouteId);
                    if (tv != null) { admins.add(tv.attendantUsername()); admins.add(tv.driverUsername()); }
                    notif.broadcast("REQ:" + id + ":WAITLIST", null, admins,
                            "已加入候补：" + s.name() + " 改乘" + mustRoute(targetRouteId).name(),
                            "目标" + eval.failureSummary() + "，家长选择候补，当前候补序号 " + (ahead + 1)
                                    + "。有名额释放时系统按序自动递补，并通知安全员确认。");
                    return cr;
                }
                // 放弃改乘：记录为已取消，原线路乘车不变
                cr = copyRequest(cr, "CANCELED", false, "SYSTEM", now(),
                        "容量不足，家长选择放弃改乘：" + eval.failureSummary(), null, null);
                store.saveRequest(cr);
                notif.broadcast("REQ:" + id + ":CANCEL", null, List.of(parent.username()),
                        "已放弃改乘：" + s.name(),
                        "因" + eval.failureSummary() + "，本次改乘已取消，孩子仍按原线路乘车，无需其他操作。");
                return cr;
            }

            store.saveRequest(cr);

            // 通知原线路 + 目标线路司机/安全员、班主任、管理员，等待目标线路安全员确认（双确认之一）
            LinkedHashSet<String> targets = partiesOf(s, targetForCapacity);
            Vehicle homeV = vehicleOfRoute(ride.routeId());
            if (homeV != null) { targets.add(homeV.driverUsername()); targets.add(homeV.attendantUsername()); }
            notif.broadcast("REQ:" + id + ":SUBMIT", null, targets,
                    "待确认：" + s.name() + " " + typeName(type) + "申请",
                    "家长提交【" + typeName(type) + "】" + requestDigest(cr)
                            + "。座位/同站点/绕行/校规校验已通过，请目标线路随车安全员核对后确认；"
                            + "通过后家长还需在手机端确认知悉，双方名单才会同步变更。");
            return cr;
        });
    }

    /** 改乘四项硬性校验的评估结果 */
    private static final class BusChangeEval {
        boolean capacityOk, seatOk, stopOk, detourOk;
        boolean hardOk;
        String capacityText, seatText, stopText, detourText;
        long aboard, sameStop;
        String failureSummary() {
            List<String> bad = new ArrayList<>();
            if (!capacityOk) bad.add("线路容量不足");
            if (!seatOk) bad.add("车辆座位不足");
            if (!stopOk) bad.add("同站点人数超限");
            if (!detourOk) bad.add("绕行时间超限");
            return String.join("、", bad);
        }
    }

    private BusChangeEval evaluateBusChange(String homeRouteId, String targetRouteId, String targetStop) {
        return evaluateBusChange(homeRouteId, targetRouteId, targetStop, null);
    }

    /**
     * 评估改乘可行性（excludeRequestId 为当前正在评估的申请，不计入预留，避免二次校验时重复计数）：
     * 1) 车辆物理座位；2) 目标站点当天上/下车人数（含已确认中名额预留）；
     * 3) 绕行时间（目标线路全程不得比原线路长 20 分钟以上）；线路容量单独展示。
     */
    private BusChangeEval evaluateBusChange(String homeRouteId, String targetRouteId, String targetStop,
                                            Long excludeRequestId) {
        Route tr = mustRoute(targetRouteId);
        Vehicle tv = vehicleOfRoute(targetRouteId);
        // 目标线路有效在乘（不含申请人）+ 已确认中但尚未生效（家长未完成双确认）的名额预留。
        // 家长已确认的申请学生已通过 ride 改挂计入 plannedCount，不再重复预留。
        long aboard = plannedCount(targetRouteId);
        final Long exclude = excludeRequestId;
        long reserved = store.listRequests().stream()
                .filter(x -> "CHANGE_BUS".equals(x.type())
                        && ("PENDING".equals(x.status())
                                || ("APPROVED".equals(x.status()) && x.parentAckAt() == null))
                        && targetRouteId.equals(x.targetRouteId())
                        && (exclude == null || !exclude.equals(x.id())))
                .count();
        long demand = aboard + reserved;

        BusChangeEval e = new BusChangeEval();
        e.aboard = demand;
        e.capacityOk = demand + 1 <= tr.capacity();
        e.capacityText = (e.capacityOk ? "容量通过：" : "容量不足：")
                + tr.name() + " 容量 " + tr.capacity() + " 人，当前计划 " + aboard
                + " 人（含确认中预留 " + reserved + "），加入后 " + (demand + 1) + " 人";

        int seats = tv == null ? 0 : tv.seats();
        e.seatOk = tv != null && demand + 1 <= seats;
        e.seatText = (e.seatOk ? "座位通过：" : "座位不足：")
                + (tv == null ? "目标线路无值班车辆"
                        : tv.plate() + " 共 " + seats + " 座，加入后需 " + (demand + 1) + " 座");

        // 同站点：当天目标线路在该站上/下车的有效人数（含预留）
        long sameStop = store.listRides(today()).stream()
                .filter(this::ridingToday)
                .filter(r -> effectiveRoute(r).equals(targetRouteId))
                .filter(r -> targetStop.equals(effectiveStop(r, false))
                        || targetStop.equals(effectiveStop(r, true)))
                .count();
        sameStop += store.listRequests().stream()
                .filter(x -> "CHANGE_BUS".equals(x.type())
                        && ("PENDING".equals(x.status())
                                || ("APPROVED".equals(x.status()) && x.parentAckAt() == null))
                        && targetRouteId.equals(x.targetRouteId()) && targetStop.equals(x.targetStop())
                        && (exclude == null || !exclude.equals(x.id())))
                .count();
        e.sameStop = sameStop;
        e.stopOk = sameStop + 1 <= tr.stopCapacity();
        e.stopText = (e.stopOk ? "同站点通过：" : "同站点人数超限：")
                + targetStop + " 当天在该站上/下车 " + sameStop + " 人，站点照护上限 "
                + tr.stopCapacity() + " 人，加入后 " + (sameStop + 1) + " 人";

        Route hr = mustRoute(homeRouteId);
        int delta = tr.estMinutes() - hr.estMinutes();
        e.detourOk = delta <= 20;
        e.detourText = (e.detourOk ? "绕行通过：" : "绕行时间超限：")
                + "原线路 " + hr.name() + " 约 " + hr.estMinutes() + " 分钟，目标线路 "
                + tr.name() + " 约 " + tr.estMinutes() + " 分钟，绕行 "
                + (delta >= 0 ? "+" : "") + delta + " 分钟（允许 +20 分钟以内）";

        e.hardOk = e.capacityOk && e.seatOk && e.stopOk && e.detourOk;
        return e;
    }

    private ChangeRequest copyRequest(ChangeRequest cr, String status, boolean waitlist,
                                      String reviewedBy, String reviewedAt, String reviewNote,
                                      String attendantAckAt, String parentAckAt) {
        return new ChangeRequest(cr.id(), cr.createdAt(), cr.studentId(), cr.parentUsername(),
                cr.date(), cr.type(), cr.targetRouteId(), cr.targetStop(), cr.alternatePickupPerson(),
                cr.reason(), status, waitlist, cr.capacityCheck(), cr.seatCheck(), cr.stopCheck(),
                cr.detourCheck(), cr.ruleCheck(), reviewedBy == null ? cr.reviewedBy() : reviewedBy,
                reviewedAt == null ? cr.reviewedAt() : reviewedAt,
                reviewNote == null ? cr.reviewNote() : reviewNote,
                attendantAckAt == null ? cr.attendantAckAt() : attendantAckAt,
                parentAckAt == null ? cr.parentAckAt() : parentAckAt);
    }

    /** 安全员确认改乘（双确认之一）：通过后进入待家长确认，原/目标线路名单暂不变 */
    public ChangeRequest reviewRequest(User reviewer, long id, boolean approve, String note) {
        requireRole(reviewer, "ATTENDANT", "ADMIN");
        return store.withLock(() -> {
            ChangeRequest cr = store.getRequest(id);
            if (cr == null) throw new ApiException("申请不存在");
            if (!"PENDING".equals(cr.status())) throw new ApiException("该申请已处理，不能重复确认");
            Student s = mustStudent(cr.studentId());
            // 安全员只能确认所属线路相关申请：改乘认目标线路，其余（请假/改下车点/代接）认学生本线
            if ("ATTENDANT".equals(reviewer.role())) {
                String scopeRoute = "CHANGE_BUS".equals(cr.type()) ? cr.targetRouteId() : s.routeId();
                if (!reviewer.scopeIds().contains(scopeRoute)) {
                    throw new ForbiddenException("越权：该申请不在您负责的线路");
                }
            }

            if (!approve) {
                ChangeRequest done = copyRequest(cr, "REJECTED", false,
                        reviewer.username(), now(), note, null, null);
                store.saveRequest(done);
                LinkedHashSet<String> parties = partiesOf(s,
                        cr.targetRouteId().isBlank() ? s.routeId() : cr.targetRouteId());
                notif.broadcast("REQ:" + id + ":REJECT", null, parties,
                        "申请已驳回：" + typeName(cr.type()),
                        "学生 " + s.name() + " 的" + typeName(cr.type()) + "申请已被"
                                + reviewer.name() + "驳回"
                                + (note == null || note.isBlank() ? "" : "，备注：" + note)
                                + "。孩子仍按原线路乘车。");
                return done;
            }

            // 安全员确认前再次校验（可能提交后名额已被占用；排除自身预留，避免重复计数）
            if ("CHANGE_BUS".equals(cr.type())) {
                BusChangeEval re = evaluateBusChange(s.routeId(), cr.targetRouteId(), cr.targetStop(), cr.id());
                if (!re.hardOk) {
                    ChangeRequest wl = copyRequest(cr, "WAITLIST", true, reviewer.username(), now(),
                            "安全员确认时名额已满：" + re.failureSummary() + "，自动转入候补", null, null);
                    store.saveRequest(wl);
                    notif.broadcast("REQ:" + id + ":TO_WAITLIST", null, List.of(cr.parentUsername()),
                            "改乘转为候补：" + s.name(),
                            "安全员确认时" + re.failureSummary() + "，申请已自动转入候补，释放名额后按序递补。");
                    return wl;
                }
            }

            // 非改乘申请：安全员确认即生效
            if (!"CHANGE_BUS".equals(cr.type())) {
                ChangeRequest done = copyRequest(cr, "APPROVED", false,
                        reviewer.username(), now(), note, now(), null);
                store.saveRequest(done);
                applyApprovedRequest(s, done);
                // 请假释放名额后，尝试递补本线候补
                if ("LEAVE".equals(cr.type())) promoteWaitlist(s.routeId());
                LinkedHashSet<String> parties = partiesOf(s, s.routeId());
                notif.broadcast("REQ:" + id + ":APPROVE", null, parties,
                        "申请已通过：" + typeName(cr.type()),
                        "学生 " + s.name() + " 的" + typeName(cr.type()) + "申请已被" + reviewer.name()
                                + "确认通过，乘车安排已更新。");
                return done;
            }

            // 改乘：安全员确认完成（第一确认），等待家长确认知悉（第二确认）后名单才变更
            ChangeRequest done = copyRequest(cr, "APPROVED", false,
                    reviewer.username(), now(), note, now(), null);
            store.saveRequest(done);
            LinkedHashSet<String> parties = partiesOf(s, cr.targetRouteId());
            Vehicle homeV = vehicleOfRoute(s.routeId());
            if (homeV != null) { parties.add(homeV.driverUsername()); parties.add(homeV.attendantUsername()); }
            notif.broadcast("REQ:" + id + ":ATT_ACK", id, parties,
                    "安全员已确认，等待家长确认：" + s.name() + " 改乘",
                    "目标线路随车安全员 " + reviewer.name() + " 已确认。请家长在手机端点击「确认知悉改乘」，"
                            + "双方名单（原线路移除 / 目标线路加入）将在家长确认后立即同步给司机与安全员。");
            return done;
        });
    }

    /** 家长确认知悉改乘（双确认之二）：双方名单在此刻同步变更 */
    public ChangeRequest parentAckRequest(User user, long id) {
        requireRole(user, "PARENT", "ADMIN");
        return store.withLock(() -> {
            ChangeRequest cr = store.getRequest(id);
            if (cr == null) throw new ApiException("申请不存在");
            Student s = mustStudent(cr.studentId());
            requireParentChild(user, s);
            if (!"CHANGE_BUS".equals(cr.type())) throw new ApiException("仅临时改乘申请需要家长确认");
            if (!"APPROVED".equals(cr.status())) {
                throw new ApiException("申请当前状态为「" + requestStatusName(cr.status()) + "」，暂不能确认");
            }
            if (cr.attendantAckAt() == null) throw new ApiException("安全员尚未确认，请等待安全员确认");
            if (cr.parentAckAt() != null) throw new ApiException("您已确认过改乘，名单已同步");

            // 家长确认时最后再校验一次容量（极短窗口内被其他候补占用时转候补；排除自身预留）
            BusChangeEval re = evaluateBusChange(s.routeId(), cr.targetRouteId(), cr.targetStop(), cr.id());
            if (!re.hardOk) {
                ChangeRequest wl = copyRequest(cr, "WAITLIST", true, cr.reviewedBy(), cr.reviewedAt(),
                        "家长确认时名额已被占用：" + re.failureSummary() + "，自动转入候补",
                        cr.attendantAckAt(), null);
                store.saveRequest(wl);
                notif.broadcast("REQ:" + id + ":TO_WAITLIST", null, List.of(cr.parentUsername()),
                        "改乘转为候补：" + s.name(), "确认时" + re.failureSummary() + "，已自动转入候补。");
                return wl;
            }

            ChangeRequest done = copyRequest(cr, "APPROVED", false,
                    cr.reviewedBy(), cr.reviewedAt(), cr.reviewNote(), cr.attendantAckAt(), now());
            store.saveRequest(done);
            // 双方名单同步：学生改挂目标线路
            applyApprovedRequest(s, done);
            // 通知原线路 + 目标线路司机和安全员：名单已即时变更
            LinkedHashSet<String> crews = new LinkedHashSet<>();
            Vehicle homeV = vehicleOfRoute(s.routeId());
            Vehicle targetV = vehicleOfRoute(cr.targetRouteId());
            if (homeV != null) { crews.add(homeV.driverUsername()); crews.add(homeV.attendantUsername()); }
            if (targetV != null) { crews.add(targetV.driverUsername()); crews.add(targetV.attendantUsername()); }
            String teacher = teacherOfClass(s.classId());
            if (teacher != null) crews.add(teacher);
            crews.addAll(admins());
            notif.broadcast("REQ:" + id + ":PARENT_ACK", id, crews,
                    "改乘已生效（双确认完成）：" + s.name(),
                    "家长已确认知悉。" + s.name() + " 已从 " + mustRoute(s.routeId()).name()
                            + " 名单移除，加入 " + mustRoute(cr.targetRouteId()).name() + "（"
                            + cr.targetStop() + " 站），司机端与安全员端名册已即时更新，"
                            + "请勿再让该生乘坐原线路车辆。");
            // 名额释放触发候补递补（目标线路新增占用后无需；原线路腾出座位需要）
            promoteWaitlist(s.routeId());
            return done;
        });
    }

    /** 家长取消尚未完成双确认的改乘，或主动放弃候补 */
    public ChangeRequest cancelRequest(User user, long id, String reason) {
        requireRole(user, "PARENT", "ADMIN");
        return store.withLock(() -> {
            ChangeRequest cr = store.getRequest(id);
            if (cr == null) throw new ApiException("申请不存在");
            Student s = mustStudent(cr.studentId());
            requireParentChild(user, s);
            boolean cancellable = "PENDING".equals(cr.status()) || "WAITLIST".equals(cr.status())
                    || ("APPROVED".equals(cr.status()) && "CHANGE_BUS".equals(cr.type())
                        && cr.parentAckAt() == null);
            if (!cancellable) {
                throw new ApiException("申请当前状态为「" + requestStatusName(cr.status())
                        + "」，不能取消；已完成双确认的改乘如需撤销请联系校车管理员");
            }
            String target = "CHANGE_BUS".equals(cr.type()) ? cr.targetRouteId() : null;
            ChangeRequest done = copyRequest(cr, "CANCELED", false,
                    user.username(), now(), reason == null || reason.isBlank() ? "家长主动取消" : reason,
                    cr.attendantAckAt(), cr.parentAckAt());
            store.saveRequest(done);
            notif.broadcast("REQ:" + id + ":CANCEL", id, partiesOf(s,
                    target == null ? s.routeId() : target),
                    "申请已取消：" + s.name() + " " + typeName(cr.type()),
                    "家长取消了该申请" + (reason == null || reason.isBlank() ? "" : "：" + reason)
                            + "，孩子按原线路乘车。");
            if (target != null) promoteWaitlist(target);
            return done;
        });
    }

    /** 候补自动递补：目标线路有名额时，按候补顺序把最早的申请转 PENDING 通知安全员确认 */
    private void promoteWaitlist(String targetRouteId) {
        List<ChangeRequest> queue = store.listRequests().stream()
                .filter(x -> "WAITLIST".equals(x.status()) && "CHANGE_BUS".equals(x.type())
                        && targetRouteId.equals(x.targetRouteId()))
                .sorted(Comparator.comparing(ChangeRequest::id))
                .toList();
        for (ChangeRequest wl : queue) {
            Student s = store.getStudent(wl.studentId());
            if (s == null) continue;
            BusChangeEval e = evaluateBusChange(s.routeId(), targetRouteId, wl.targetStop(), wl.id());
            if (!e.hardOk) break; // 队首都放不下，后面更放不下
            ChangeRequest back = copyRequest(wl, "PENDING", false,
                    "SYSTEM", now(), "候补名额释放，自动递补请安全员确认", null, null);
            store.saveRequest(back);
            Vehicle tv = vehicleOfRoute(targetRouteId);
            LinkedHashSet<String> parties = new LinkedHashSet<>();
            parties.add(wl.parentUsername());
            if (tv != null) { parties.add(tv.attendantUsername()); parties.add(tv.driverUsername()); }
            parties.add(teacherOfClass(s.classId()));
            parties.addAll(admins());
            notif.broadcast("REQ:" + wl.id() + ":PROMOTE", null, parties,
                    "候补递补：" + s.name() + " 改乘" + mustRoute(targetRouteId).name(),
                    "目标线路已腾出名额，候补申请自动转为待确认，请随车安全员核对座位/同站点/绕行后确认。");
        }
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

    public String requestStatusName(String st) {
        return switch (st) {
            case "PENDING" -> "待安全员确认";
            case "WAITLIST" -> "候补中";
            case "APPROVED" -> "已通过";
            case "REJECTED" -> "已驳回";
            case "CANCELED" -> "已放弃/取消";
            default -> st;
        };
    }

    /** 司机端名单：只能查看本人车辆所跑线路的早晨/放学名单（改乘通过后立即在此更新） */
    public Map<String, Object> driverRoster(User driver) {
        requireRole(driver, "DRIVER", "ADMIN");
        List<Vehicle> vehicles = store.listVehicles().stream()
                .filter(v -> "ADMIN".equals(driver.role()) || driver.scopeIds().contains(v.id()))
                .toList();
        if (vehicles.isEmpty()) throw new ApiException("当前账号下没有值班车辆");
        List<Map<String, Object>> routes = new ArrayList<>();
        for (Vehicle v : vehicles) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("vehicle", v);
            m.put("route", mustRoute(v.routeId()));
            m.put("morning", buildMorningRoster(v.routeId()));
            m.put("afternoon", buildAfternoonRoster(v.routeId()));
            routes.add(m);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("date", today());
        resp.put("routes", routes);
        return resp;
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
        requireRole(user, "PARENT", "ADMIN");
        Student s = mustStudent(studentId);
        requireParentChild(user, s);
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
        requireRole(user, "TEACHER", "ADMIN");
        Student s = mustStudent(studentId);
        requireTeacherClass(user, s);
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

    public List<Map<String, Object>> morningRoster(User viewer, String routeId) {
        requireRosterViewAccess(viewer, routeId);
        return buildMorningRoster(routeId);
    }

    private void requireRosterViewAccess(User viewer, String routeId) {
        requireRole(viewer, "ATTENDANT", "DRIVER", "ADMIN");
        if ("ADMIN".equals(viewer.role())) return;
        if ("ATTENDANT".equals(viewer.role())) {
            if (!viewer.scopeIds().contains(routeId)) {
                throw new ForbiddenException("越权：只能查看您所属线路的名单");
            }
            return;
        }
        // 司机只能查看本人车辆所跑线路
        Vehicle v = vehicleOfRoute(routeId);
        if (v == null || !viewer.username().equals(v.driverUsername())) {
            throw new ForbiddenException("越权：该名单不属于您驾驶的车辆");
        }
    }

    private List<Map<String, Object>> buildMorningRoster(String routeId) {
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
        requireRole(attendant, "ATTENDANT", "ADMIN");
        if (!List.of("BOARDED", "ABSENT_NO_SHOW", "LATE", "TEMP_BOARDED").contains(status)) {
            throw new ApiException("点名状态非法");
        }
        Student s = mustStudent(studentId);
        return store.withLock(() -> {
            RideStatus r = rideOf(studentId);
            if (!ridingToday(r)) throw new ApiException("该生今天请假，不参与点名");
            // scope：安全员只能给自己所属线路的学生点名（以当天有效线路为准）
            requireRosterAccess(attendant, s, effectiveRoute(r));
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
        requireRole(actor, "ATTENDANT", "ADMIN");
        Student s = mustStudent(studentId);
        Vehicle v = store.getVehicle(vehicleId);
        if (v == null) throw new ApiException("车辆不存在");
        // scope：安全员必须属于学生有效线路或当前所在车辆线路之一
        if ("ATTENDANT".equals(actor.role())) {
            RideStatus cur = rideOf(studentId);
            String studentRoute = effectiveRoute(cur);
            if (!actor.scopeIds().contains(studentRoute) && !actor.scopeIds().contains(v.routeId())) {
                throw new ForbiddenException("越权：该生与车辆均不在您负责的线路");
            }
        }
        return store.withLock(() -> {
            Incident existing = openStudentIncident("WRONG_BUS", studentId);
            if (existing != null) {
                throw new ApiException("已存在处理中的「上错车」异常事件 #" + existing.id()
                        + "，请在原事件上更新进展");
            }
            // 学生本线司乘与实际所在车辆线路司乘都要串进同一事件
            LinkedHashSet<String> extra = new LinkedHashSet<>(partiesOf(s, effectiveRoute(rideOf(studentId))));
            return openIncident("WRONG_BUS", s, v.routeId(),
                    "车上（" + v.plate() + "）", actor,
                    s.name() + " 上错车，当前在 " + v.plate() + "（" + mustRoute(v.routeId()).name() + "）", extra);
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
        requireVehicleAccess(driver, v);
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

    public Map<String, Object> afternoonRoster(User viewer, String routeId) {
        requireRosterViewAccess(viewer, routeId);
        return buildAfternoonRoster(routeId);
    }

    private Map<String, Object> buildAfternoonRoster(String routeId) {
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
        requireRole(actor, "ATTENDANT", "ADMIN");
        if (!List.of("ONBOARD", "DELIVERED", "NOT_BOARDED", "NO_PICKUP", "WRONG_BUS").contains(status)) {
            throw new ApiException("放学点名状态非法");
        }
        Student s = mustStudent(studentId);
        return store.withLock(() -> {
            RideStatus r = rideOf(studentId);
            if (!ridingToday(r)) throw new ApiException("该生今天请假");
            requireRosterAccess(actor, s, effectiveRoute(r));
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
        requireRole(actor, "PARENT", "ADMIN");
        Student s = mustStudent(studentId);
        requireParentChild(actor, s);
        return store.withLock(() -> {
            RideStatus r = rideOf(studentId);
            String routeId = effectiveRoute(r);
            return openIncident("PICKUP_CHANGE", s, routeId, effectiveStop(r, true), actor,
                    "家长临时变更接送人：" + description, null);
        });
    }

    /** 家长确认孩子已接到（仅本人监护学生） */
    public RideStatus parentConfirm(User user, String studentId) {
        requireRole(user, "PARENT", "ADMIN");
        Student s = mustStudent(studentId);
        requireParentChild(user, s);
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

    public List<Incident> incidents(User viewer, String status, String routeId) {
        return visibleIncidents(viewer).stream()
                .filter(i -> status == null || status.isBlank() || status.equals(i.status()))
                .filter(i -> routeId == null || routeId.isBlank() || routeId.equals(i.routeId()))
                .toList();
    }

    /** 事件可见性：管理员全部；其余角色必须是该事件通知的接收方（即被串联进同一事件的五方人员） */
    public List<Incident> visibleIncidents(User viewer) {
        if ("ADMIN".equals(viewer.role())) return store.listIncidents();
        java.util.Set<Long> mine = new java.util.HashSet<>();
        for (Notification n : store.listNotificationsByUser(viewer.username())) {
            if (n.incidentId() != null) mine.add(n.incidentId());
        }
        return store.listIncidents().stream().filter(i -> mine.contains(i.id())).toList();
    }

    public List<ChangeRequest> visibleRequests(User viewer) {
        if ("ADMIN".equals(viewer.role())) return store.listRequests();
        if ("DRIVER".equals(viewer.role())) return List.of();
        return store.listRequests().stream().filter(cr -> {
            Student s = store.getStudent(cr.studentId());
            if (s == null) return false;
            return switch (viewer.role()) {
                case "PARENT" -> viewer.username().equals(cr.parentUsername());
                case "TEACHER" -> viewer.scopeIds().contains(s.classId());
                case "ATTENDANT" -> viewer.scopeIds().contains(s.routeId())
                        || (cr.targetRouteId() != null && viewer.scopeIds().contains(cr.targetRouteId()));
                default -> false;
            };
        }).toList();
    }

    private Incident requireViewIncident(User viewer, long id) {
        Incident i = incident(id);
        requireIncidentAccess(viewer, i);
        return i;
    }

    /** 内部按主键取事件（不做鉴权），供同事务内的写流程使用 */
    public Incident incident(long id) {
        Incident i = store.getIncident(id);
        if (i == null) throw new ApiException("事件不存在");
        return i;
    }

    public Incident viewIncident(User viewer, long id) {
        return requireViewIncident(viewer, id);
    }

    public List<Notification> incidentReceipts(User viewer, long incidentId) {
        requireViewIncident(viewer, incidentId);
        return store.listNotificationsByIncident(incidentId);
    }

    public Incident appendAction(User actor, long incidentId, String action) {
        requireRole(actor, "ADMIN", "DRIVER", "ATTENDANT", "TEACHER", "PARENT");
        if (action == null || action.isBlank()) throw new ApiException("处理动作不能为空");
        Incident gate = incident(incidentId);
        requireIncidentAccess(actor, gate);
        if ("RESOLVED".equals(gate.status())) throw new ApiException("事件已关闭，不能再更新");
        return store.withLock(() -> {
            Incident inc = incident(incidentId);
            appendTimeline(inc, actor, action);
            notif.broadcast("ACTION:" + incidentId, incidentId, partiesOfIncident(inc),
                    "事件进展 #" + incidentId, actor.name() + "：" + action);
            return store.getIncident(incidentId);
        });
    }

    /** 催办未回执方：仅校车管理员（学校核对回执的动作） */
    public int nudge(User actor, long incidentId) {
        requireRole(actor, "ADMIN");
        incident(incidentId);
        return notif.nudge(incidentId);
    }

    public ArchiveEntry resolve(User admin, long incidentId, String resolution, String responsibility) {
        requireRole(admin, "ADMIN");
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

    public List<ArchiveEntry> archives(User viewer, String studentId) {
        requireRole(viewer, "ADMIN", "TEACHER", "PARENT");
        if ("ADMIN".equals(viewer.role())) {
            if (studentId != null && !studentId.isBlank()) return store.listArchivesByStudent(studentId);
            return store.listArchives();
        }
        // 班主任仅授权班级；家长仅本人监护学生
        if (studentId != null && !studentId.isBlank()) {
            Student s = mustStudent(studentId);
            requireParentChild(viewer, s);
            requireTeacherClass(viewer, s);
            return store.listArchivesByStudent(studentId);
        }
        return store.listArchives().stream().filter(a -> {
            Student s = store.getStudent(a.studentId());
            if (s == null) return false;
            return "PARENT".equals(viewer.role())
                    ? viewer.scopeIds().contains(s.id())
                    : viewer.scopeIds().contains(s.classId());
        }).toList();
    }

    // ================= 复盘 / 统一事实 =================

    /** 异常高发站点复盘：仅校车管理员（可按线路过滤） */
    public Map<String, Object> hotspotReview(User viewer, String routeId) {
        requireRole(viewer, "ADMIN");
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
