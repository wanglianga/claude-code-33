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
        d.put("requests", svc.requests());
        d.put("incidents", svc.incidents(null, null));
        d.put("notifications", store.listNotificationsByUser(u.username()));
        d.put("myNotificationsUnacked",
                store.listNotificationsByUser(u.username()).stream().filter(n -> !n.ack()).count());
        d.put("facts", svc.dailyFacts());
        if ("ADMIN".equals(u.role())) {
            d.put("archives", svc.archives(null));
            d.put("review", svc.hotspotReview(null));
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

    // ---------- 点名 / 名单 ----------

    @GetMapping("/roster/morning")
    public Map<String, Object> morningRoster(@RequestParam String routeId) {
        return ok(svc.morningRoster(routeId));
    }

    @GetMapping("/roster/afternoon")
    public Map<String, Object> afternoonRoster(@RequestParam String routeId) {
        return ok(svc.afternoonRoster(routeId));
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
                str(body, "locationText"), integer(body, "delayMinutes")));
    }

    // ---------- 家长备注 / 家长确认 ----------

    @PostMapping("/parent/note")
    public Map<String, Object> note(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        return ok(svc.parentNote(me(req), str(body, "studentId"), str(body, "note")));
    }

    @PostMapping("/parent/confirm")
    public Map<String, Object> confirm(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        return ok(svc.parentConfirm(str(body, "studentId")));
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
    public Map<String, Object> incidents(@RequestParam(required = false) String status,
                                         @RequestParam(required = false) String routeId) {
        return ok(svc.incidents(status, routeId));
    }

    @GetMapping("/incidents/{id}")
    public Map<String, Object> incident(@PathVariable long id) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("incident", svc.incident(id));
        d.put("receipts", svc.incidentReceipts(id));
        return ok(d);
    }

    @PostMapping("/incidents/{id}/action")
    public Map<String, Object> action(HttpServletRequest req, @PathVariable long id,
                                      @RequestBody Map<String, Object> body) {
        return ok(svc.appendAction(me(req), id, str(body, "action")));
    }

    @GetMapping("/incidents/{id}/receipts")
    public Map<String, Object> receipts(@PathVariable long id) {
        return ok(svc.incidentReceipts(id));
    }

    @PostMapping("/incidents/{id}/nudge")
    public Map<String, Object> nudge(@PathVariable long id) {
        return ok(Map.of("resent", notif.nudge(id)));
    }

    @PostMapping("/incidents/{id}/resolve")
    public Map<String, Object> resolve(HttpServletRequest req, @PathVariable long id,
                                       @RequestBody Map<String, Object> body) {
        return ok(svc.resolve(me(req), id, str(body, "resolution"), str(body, "responsibility")));
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
    public Map<String, Object> archives(@RequestParam(required = false) String studentId) {
        return ok(svc.archives(studentId));
    }

    @GetMapping("/review/hotspots")
    public Map<String, Object> hotspots(@RequestParam(required = false) String routeId) {
        return ok(svc.hotspotReview(routeId));
    }

    @GetMapping("/facts")
    public Map<String, Object> facts() {
        return ok(svc.dailyFacts());
    }
}
