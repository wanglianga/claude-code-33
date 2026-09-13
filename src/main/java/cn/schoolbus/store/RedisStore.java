package cn.schoolbus.store;

import cn.schoolbus.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;

/**
 * 基于 Redis 的 JSON 存储：实体 sb:{type}:{id}，集合索引 sb:idx:{...}，序列号 sb:seq:{name}。
 * 所有写操作走同一把短 TTL 分布式锁，保证"点名 / 申请 / 异常 / 通知"对同一份乘车事实串行更新。
 */
@Component
public class RedisStore {

    private final StringRedisTemplate redis;
    private final ObjectMapper om = new ObjectMapper()
            .findAndRegisterModules();

    private static final String LOCK_KEY = "sb:lock:write";

    public RedisStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // ---------- 通用 ----------

    public <T> T withLock(Supplier<T> action) {
        String token = UUID.randomUUID().toString();
        for (int i = 0; i < 30; i++) {
            Boolean ok = redis.opsForValue().setIfAbsent(LOCK_KEY, token, Duration.ofSeconds(10));
            if (Boolean.TRUE.equals(ok)) {
                try {
                    return action.get();
                } finally {
                    // 仅持有者释放（Lua 保证）
                    redis.execute(
                            new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                                    "if redis.call('get',KEYS[1])==ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end",
                                    Long.class),
                            List.of(LOCK_KEY), token);
                }
            }
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        throw new IllegalStateException("系统繁忙，请稍后重试");
    }

    public void withLock(Runnable action) {
        withLock(() -> { action.run(); return null; });
    }

    public long nextId(String name) {
        return redis.opsForValue().increment("sb:seq:" + name);
    }

    private void put(String key, Object value) {
        try {
            redis.opsForValue().set(key, om.writeValueAsString(value));
        } catch (Exception e) {
            throw new IllegalStateException("序列化失败: " + key, e);
        }
    }

    private <T> T get(String key, Class<T> type) {
        String json = redis.opsForValue().get(key);
        if (json == null) return null;
        try {
            return om.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("反序列化失败: " + key, e);
        }
    }

    private <T> List<T> byIndex(String idxKey, String keyPrefix, Class<T> type) {
        Set<String> ids = redis.opsForSet().members(idxKey);
        if (ids == null || ids.isEmpty()) return new ArrayList<>();
        List<T> out = new ArrayList<>();
        for (String id : ids) {
            T v = get(keyPrefix + id, type);
            if (v != null) out.add(v);
        }
        return out;
    }

    // ---------- 用户 ----------

    public void saveUser(User u) {
        put("sb:user:" + u.username(), u);
        redis.opsForSet().add("sb:idx:users", u.username());
    }

    public User getUser(String username) {
        return get("sb:user:" + username, User.class);
    }

    public List<User> listUsers() {
        return byIndex("sb:idx:users", "sb:user:", User.class);
    }

    // ---------- 学生 ----------

    public void saveStudent(Student s) {
        put("sb:student:" + s.id(), s);
        redis.opsForSet().add("sb:idx:students", s.id());
    }

    public Student getStudent(String id) {
        return get("sb:student:" + id, Student.class);
    }

    public List<Student> listStudents() {
        return byIndex("sb:idx:students", "sb:student:", Student.class);
    }

    // ---------- 线路 ----------

    public void saveRoute(Route r) {
        put("sb:route:" + r.id(), r);
        redis.opsForSet().add("sb:idx:routes", r.id());
    }

    public Route getRoute(String id) {
        return get("sb:route:" + id, Route.class);
    }

    public List<Route> listRoutes() {
        return byIndex("sb:idx:routes", "sb:route:", Route.class);
    }

    // ---------- 车辆 ----------

    public void saveVehicle(Vehicle v) {
        put("sb:vehicle:" + v.id(), v);
        redis.opsForSet().add("sb:idx:vehicles", v.id());
    }

    public Vehicle getVehicle(String id) {
        return get("sb:vehicle:" + id, Vehicle.class);
    }

    public List<Vehicle> listVehicles() {
        return byIndex("sb:idx:vehicles", "sb:vehicle:", Vehicle.class);
    }

    // ---------- 当天乘车事实 ----------

    public void saveRide(RideStatus r) {
        put("sb:ride:" + r.date() + ":" + r.studentId(), r);
        redis.opsForSet().add("sb:idx:rides:" + r.date(), r.studentId());
    }

    public RideStatus getRide(String date, String studentId) {
        return get("sb:ride:" + date + ":" + studentId, RideStatus.class);
    }

    public List<RideStatus> listRides(String date) {
        return byIndex("sb:idx:rides:" + date, "sb:ride:" + date + ":", RideStatus.class);
    }

    // ---------- 家长申请 ----------

    public void saveRequest(ChangeRequest r) {
        put("sb:request:" + r.id(), r);
        redis.opsForSet().add("sb:idx:requests", String.valueOf(r.id()));
    }

    public ChangeRequest getRequest(long id) {
        return get("sb:request:" + id, ChangeRequest.class);
    }

    public List<ChangeRequest> listRequests() {
        List<ChangeRequest> all = byIndex("sb:idx:requests", "sb:request:", ChangeRequest.class);
        all.sort(Comparator.comparing(ChangeRequest::id).reversed());
        return all;
    }

    // ---------- 异常事件 ----------

    public void saveIncident(Incident i) {
        put("sb:incident:" + i.id(), i);
        redis.opsForSet().add("sb:idx:incidents", String.valueOf(i.id()));
        redis.opsForSet().add("sb:idx:incidents:student:" + i.studentId(), String.valueOf(i.id()));
    }

    public Incident getIncident(long id) {
        return get("sb:incident:" + id, Incident.class);
    }

    public List<Incident> listIncidents() {
        List<Incident> all = byIndex("sb:idx:incidents", "sb:incident:", Incident.class);
        all.sort(Comparator.comparing(Incident::id).reversed());
        return all;
    }

    public List<Incident> listIncidentsByStudent(String studentId) {
        return byIndex("sb:idx:incidents:student:" + studentId, "sb:incident:", Incident.class);
    }

    // ---------- 通知回执 ----------

    public void saveNotification(Notification n) {
        put("sb:notif:" + n.id(), n);
        redis.opsForSet().add("sb:idx:notifs", String.valueOf(n.id()));
        redis.opsForSet().add("sb:idx:notif:user:" + n.recipientUsername(), String.valueOf(n.id()));
        if (n.incidentId() != null) {
            redis.opsForSet().add("sb:idx:notif:inc:" + n.incidentId(), String.valueOf(n.id()));
        }
    }

    public Notification getNotification(long id) {
        return get("sb:notif:" + id, Notification.class);
    }

    public List<Notification> listNotifications() {
        return byIndex("sb:idx:notifs", "sb:notif:", Notification.class);
    }

    public List<Notification> listNotificationsByUser(String username) {
        List<Notification> list = byIndex("sb:idx:notif:user:" + username, "sb:notif:", Notification.class);
        list.sort(Comparator.comparing(Notification::id).reversed());
        return list;
    }

    public List<Notification> listNotificationsByIncident(long incidentId) {
        List<Notification> list = byIndex("sb:idx:notif:inc:" + incidentId, "sb:notif:", Notification.class);
        list.sort(Comparator.comparing(Notification::id));
        return list;
    }

    // ---------- 乘车档案 ----------

    public void saveArchive(ArchiveEntry a) {
        put("sb:archive:" + a.id(), a);
        redis.opsForSet().add("sb:idx:archives", a.id());
        redis.opsForSet().add("sb:idx:archive:student:" + a.studentId(), a.id());
    }

    public List<ArchiveEntry> listArchives() {
        List<ArchiveEntry> all = byIndex("sb:idx:archives", "sb:archive:", ArchiveEntry.class);
        all.sort(Comparator.comparing(ArchiveEntry::archivedAt).reversed());
        return all;
    }

    public List<ArchiveEntry> listArchivesByStudent(String studentId) {
        Set<String> ids = redis.opsForSet().members("sb:idx:archive:student:" + studentId);
        if (ids == null) return List.of();
        List<ArchiveEntry> out = new ArrayList<>();
        for (String id : ids) {
            String json = redis.opsForValue().get("sb:archive:" + id);
            if (json != null) {
                try { out.add(om.readValue(json, ArchiveEntry.class)); } catch (Exception ignored) { }
            }
        }
        out.sort(Comparator.comparing(ArchiveEntry::archivedAt).reversed());
        return out;
    }

    // ---------- 运营日期（支持复盘后滚动到次日名单） ----------

    public String getOpsDate() {
        String d = redis.opsForValue().get("sb:ops:date");
        return d == null || d.isBlank() ? java.time.LocalDate.now().toString() : d;
    }

    public void setOpsDate(String date) {
        redis.opsForValue().set("sb:ops:date", date);
    }

    // ---------- 次日重点关注名单（上错车复盘联动） ----------

    public void addWatch(String date, String studentId) {
        redis.opsForSet().add("sb:watch:" + date, studentId);
    }

    public void removeWatch(String date, String studentId) {
        redis.opsForSet().remove("sb:watch:" + date, studentId);
    }

    public java.util.Set<String> listWatch(String date) {
        java.util.Set<String> s = redis.opsForSet().members("sb:watch:" + date);
        return s == null ? java.util.Set.of() : s;
    }

    // ---------- 运维 ----------

    @SuppressWarnings("unchecked")
    public Map<String, Object> info() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("students", redis.opsForSet().size("sb:idx:students"));
        m.put("routes", redis.opsForSet().size("sb:idx:routes"));
        m.put("vehicles", redis.opsForSet().size("sb:idx:vehicles"));
        m.put("requests", redis.opsForSet().size("sb:idx:requests"));
        m.put("incidents", redis.opsForSet().size("sb:idx:incidents"));
        m.put("notifications", redis.opsForSet().size("sb:idx:notifs"));
        m.put("archives", redis.opsForSet().size("sb:idx:archives"));
        try {
            Properties p = redis.execute((org.springframework.data.redis.core.RedisCallback<Properties>) c -> c.info("server"));
            if (p != null) m.put("redisVersion", p.getProperty("redis_version"));
        } catch (Exception ignored) { }
        return m;
    }
}
