package cn.schoolbus.api;

import cn.schoolbus.domain.User;
import cn.schoolbus.service.NotificationService;
import cn.schoolbus.service.SchoolBusService;
import cn.schoolbus.store.RedisStore;
import cn.schoolbus.support.ApiException;
import cn.schoolbus.support.AuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final SchoolBusService svc;
    private final NotificationService notif;
    private final RedisStore store;

    public ApiController(SchoolBusService svc, NotificationService notif, RedisStore store) {
        this.svc = svc;
        this.notif = notif;
        this.store = store;
    }

    private User me(HttpServletRequest req) {
        return (User) req.getAttribute(AuthInterceptor.CURRENT_USER);
    }

    private Map<String, Object> ok(Object data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("data", data);
        return m;
    }

    private String str(Map<String, Object> b, String key) {
        Object v = b.get(key);
        return v == null ? "" : String.valueOf(v).trim();
    }

    private boolean bool(Map<String, Object> b, String key) {
        Object v = b.get(key);
        return v != null && Boolean.parseBoolean(String.valueOf(v));
    }

    private Integer integer(Map<String, Object> b, String key) {
        Object v = b.get(key);
        if (v == null || String.valueOf(v).isBlank()) return null;
        return Integer.valueOf(String.valueOf(v));
    }

    // ---------- 健康 / 登录 ----------

    @GetMapping("/health")
    public Map<String, Object> health() {
        return ok(Map.of("status", "UP", "redis", store.info()));
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, Object> body) {
        User u = svc.login(str(body, "username"), str(body, "password"));
        return ok(u);
    }

    /** 首屏一次性拉取演示所需全部基础数据 */
    @GetMapping("/bootstrap")
    public Map<String, Object> bootstrap(HttpServletRequest req) {
        User u = me(req);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("user", u);
        d.put("students", svc.students());
        d.put("routes", svc.routes());
        d.put("vehicles", svc.vehicles());
        d.put("requests", svc.visibleRequests(u));
        d.put("incidents", svc.incidents(u, null, null));
        d.put("notifications", store.listNotificationsByUser(u.username()));
        d.put("myNotificationsUnacked",
                store.listNotificationsByUser(u.username()).stream().filter(n -> !n.ack()).count());
        d.put("facts", svc.dailyFacts());
        if ("DRIVER".equals(u.role()) || "ADMIN".equals(u.role())) {
            d.put("driverRoster", svc.driverRoster(u));
        }
        if ("ADMIN".equals(u.role())) {
            d.put("archives", svc.archives(u, null));
            d.put("review", svc.hotspotReview(u, null));
            d.put("storeInfo", store.info());
        }
        return ok(d);
    }

    // ---------- 家长申请 ----------

    @PostMapping("/requests")
    public Map<String, Object> submit(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        Map<String, String> b2 = new LinkedHashMap<>();
        body.forEach((k, v) -> b2.put(k, v == null ? "" : String.valueOf(v)));
        return ok(svc.submitRequest(me(req), b2));
    }

    @PostMapping("/requests/{id}/review")
    public Map<String, Object> review(HttpServletRequest req, @PathVariable long id,
                                      @RequestBody Map<String, Object> body) {
        return ok(svc.reviewRequest(me(req), id, bool(body, "approve"), str(body, "note")));
    }

    /** 家长确认知悉改乘（双确认之二），确认后原/目标线路名单立即同步 */
    @PostMapping("/requests/{id}/parent-ack")
    public Map<String, Object> parentAck(HttpServletRequest req, @PathVariable long id) {
        return ok(svc.parentAckRequest(me(req), id));
    }

    /** 家长放弃候补 / 取消尚未完成双确认的申请 */
    @PostMapping("/requests/{id}/cancel")
    public Map<String, Object> cancel(HttpServletRequest req, @PathVariable long id,
                                      @RequestBody(required = false) Map<String, Object> body) {
        return ok(svc.cancelRequest(me(req), id, body == null ? "" : str(body, "reason")));
    }

    // ---------- 点名 / 名单 ----------

    @GetMapping("/roster/morning")
    public Map<String, Object> morningRoster(HttpServletRequest req, @RequestParam String routeId) {
        return ok(svc.morningRoster(me(req), routeId));
    }

    @GetMapping("/roster/afternoon")
    public Map<String, Object> afternoonRoster(HttpServletRequest req, @RequestParam String routeId) {
        return ok(svc.afternoonRoster(me(req), routeId));
    }

    @PostMapping("/morning/mark")
    public Map<String, Object> markMorning(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        return ok(svc.markMorning(me(req), str(body, "studentId"), str(body, "status")));
    }

    @PostMapping("/afternoon/mark")
    public Map<String, Object> markAfternoon(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        return ok(svc.markAfternoon(me(req), str(body, "studentId"), str(body, "status")));
    }

    @PostMapping("/wrong-bus")
    public Map<String, Object> wrongBus(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        return ok(svc.markWrongBus(me(req), str(body, "studentId"), str(body, "vehicleId")));
    }

    // ---------- 车辆实时状态 ----------

    @PostMapping("/vehicle/status")
    public Map<String, Object> vehicleStatus(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        return ok(svc.updateVehicle(me(req), str(body, "vehicleId"), str(body, "status"),
                str(body, "locationText"), str(body, "nearestStop"), integer(body, "delayMinutes")));
    }

    /** 司机端查看本人车辆的早晨/放学名单（改乘通过后立即同步） */
    @GetMapping("/driver/roster")
    public Map<String, Object> driverRoster(HttpServletRequest req) {
        return ok(svc.driverRoster(me(req)));
    }

    // ---------- 家长备注 / 家长确认 ----------

    @PostMapping("/parent/note")
    public Map<String, Object> note(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        return ok(svc.parentNote(me(req), str(body, "studentId"), str(body, "note")));
    }

    @PostMapping("/parent/confirm")
    public Map<String, Object> confirm(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        return ok(svc.parentConfirm(me(req), str(body, "studentId")));
    }

    @PostMapping("/incident/report-pickup-change")
    public Map<String, Object> pickupChange(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        return ok(svc.reportPickupChange(me(req), str(body, "studentId"), str(body, "description")));
    }

    // ---------- 班主任：社团登记 ----------

    @PostMapping("/teacher/club")
    public Map<String, Object> club(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        return ok(svc.setClub(me(req), str(body, "studentId"), bool(body, "club")));
    }

    // ---------- 异常事件 ----------

    @GetMapping("/incidents")
    public Map<String, Object> incidents(HttpServletRequest req,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String routeId) {
        return ok(svc.incidents(me(req), status, routeId));
    }

    @GetMapping("/incidents/{id}")
    public Map<String, Object> incident(HttpServletRequest req, @PathVariable long id) {
        return ok(svc.incidentDetail(me(req), id));
    }

    /** 上错车处置方案：执行某一步并同步五方处理进度 */
    @PostMapping("/incidents/{id}/plan-step")
    public Map<String, Object> planStep(HttpServletRequest req, @PathVariable long id,
                                        @RequestBody Map<String, Object> body) {
        int step = Integer.parseInt(String.valueOf(body.getOrDefault("step", "0")));
        return ok(svc.executePlanStep(me(req), id, step, str(body, "note")));
    }

    /** 管理员滚动到下一运营日（上错车复盘后联动次日名单） */
    @PostMapping("/admin/roll-day")
    public Map<String, Object> rollDay(HttpServletRequest req) {
        return ok(svc.rollNextDay(me(req)));
    }

    @PostMapping("/incidents/{id}/action")
    public Map<String, Object> action(HttpServletRequest req, @PathVariable long id,
                                      @RequestBody Map<String, Object> body) {
        return ok(svc.appendAction(me(req), id, str(body, "action")));
    }

    @GetMapping("/incidents/{id}/receipts")
    public Map<String, Object> receipts(HttpServletRequest req, @PathVariable long id) {
        return ok(svc.incidentReceipts(me(req), id));
    }

    @PostMapping("/incidents/{id}/nudge")
    public Map<String, Object> nudge(HttpServletRequest req, @PathVariable long id) {
        return ok(Map.of("resent", svc.nudge(me(req), id)));
    }

    @PostMapping("/incidents/{id}/resolve")
    public Map<String, Object> resolve(HttpServletRequest req, @PathVariable long id,
                                       @RequestBody Map<String, Object> body) {
        return ok(svc.resolve(me(req), id, str(body, "resolution"), str(body, "responsibility"),
                str(body, "rootCause"), str(body, "prevention")));
    }

    // ---------- 通知回执 ----------

    @GetMapping("/notifications/mine")
    public Map<String, Object> mine(HttpServletRequest req) {
        return ok(store.listNotificationsByUser(me(req).username()));
    }

    @PostMapping("/notifications/{id}/ack")
    public Map<String, Object> ack(HttpServletRequest req, @PathVariable long id) {
        return ok(notif.ack(id, me(req).username()));
    }

    // ---------- 档案 / 复盘 / 统一事实 ----------

    @GetMapping("/archives")
    public Map<String, Object> archives(HttpServletRequest req,
                                        @RequestParam(required = false) String studentId) {
        return ok(svc.archives(me(req), studentId));
    }

    @GetMapping("/review/hotspots")
    public Map<String, Object> hotspots(HttpServletRequest req,
                                        @RequestParam(required = false) String routeId) {
        return ok(svc.hotspotReview(me(req), routeId));
    }

    @GetMapping("/facts")
    public Map<String, Object> facts() {
        return ok(svc.dailyFacts());
    }
}
