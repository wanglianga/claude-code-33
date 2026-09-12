package cn.schoolbus.bootstrap;

import cn.schoolbus.domain.*;
import cn.schoolbus.store.RedisStore;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** 首次启动写入演示数据：线路、车辆、学生（含默认乘车配置）与五角色账号，并生成当天 PLANNED 乘车事实。 */
@Component
public class DataSeeder {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final RedisStore store;

    public DataSeeder(RedisStore store) {
        this.store = store;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        if (!store.listStudents().isEmpty()) {
            return;
        }
        store.withLock(() -> {
            if (!store.listStudents().isEmpty()) {
                return;
            }
            doSeed();
        });
    }

    private void doSeed() {
        String now = LocalDateTime.now().format(TS);
        String date = LocalDate.now().toString();

        // ---------- 账号 ----------
        store.saveUser(new User("admin", "admin123", "周校长", "ADMIN", "13900000000", List.of("*")));
        store.saveUser(new User("dli", "driver123", "李建国", "DRIVER", "13900000001", List.of("V1")));
        store.saveUser(new User("wangshifu", "driver123", "王建国", "DRIVER", "13900000002", List.of("V2")));
        store.saveUser(new User("anyi", "att123", "安一路", "ATTENDANT", "13900000003", List.of("R1", "V1")));
        store.saveUser(new User("liantai", "att123", "廉泰", "ATTENDANT", "13900000004", List.of("R2", "V2")));
        store.saveUser(new User("teacher31", "teacher123", "王老师", "TEACHER", "13900000101", List.of("C31")));
        store.saveUser(new User("teacher32", "teacher123", "李老师", "TEACHER", "13900000102", List.of("C32")));
        store.saveUser(new User("zhangfu", "parent123", "张父", "PARENT", "13800000001", List.of("S1")));
        store.saveUser(new User("limu", "parent123", "李母", "PARENT", "13800000002", List.of("S2")));
        store.saveUser(new User("wangma", "parent123", "王母", "PARENT", "13800000003", List.of("S3")));
        store.saveUser(new User("zhaoba", "parent123", "赵父", "PARENT", "13800000004", List.of("S4")));
        store.saveUser(new User("chenma", "parent123", "陈母", "PARENT", "13800000005", List.of("S5")));

        // ---------- 车辆 ----------
        store.saveVehicle(new Vehicle("V1", "京A·1001", 20, "R1", "dli", "anyi",
                "ON_TIME", "学校停车场", 0, now));
        store.saveVehicle(new Vehicle("V2", "京A·2002", 3, "R2", "wangshifu", "liantai",
                "ON_TIME", "学校停车场", 0, now));

        // ---------- 线路 ----------
        store.saveRoute(new Route("R1", "一号线（滨江线）", "BOTH", "V1",
                List.of("阳光花园东门", "滨江路站", "市民中心", "实验学校"), 20));
        // 二号线 deliberately 满员：3 名默认乘客 / 容量 3 / 车辆 3 座，用于演示容量与座位校验
        store.saveRoute(new Route("R2", "二号线（翠湖线）", "BOTH", "V2",
                List.of("翠湖天地", "科技园北门", "实验学校"), 3));

        // ---------- 学生档案（默认线路、上下车点、接送人、班级、家长电话、特殊照护） ----------
        store.saveStudent(new Student("S1", "张小明", "20260101", "C31", "三年级(1)班",
                "R1", "滨江路站", "张父", "13800000001", "zhangfu", false, ""));
        store.saveStudent(new Student("S2", "李小华", "20260102", "C31", "三年级(1)班",
                "R1", "阳光花园东门", "李母", "13800000002", "limu", true, "哮喘，需随车照护"));
        store.saveStudent(new Student("S3", "王小芳", "20260201", "C32", "三年级(2)班",
                "R2", "翠湖天地", "王母", "13800000003", "wangma", false, ""));
        store.saveStudent(new Student("S4", "赵小强", "20260202", "C32", "三年级(2)班",
                "R2", "科技园北门", "赵父", "13800000004", "zhaoba", false, ""));
        store.saveStudent(new Student("S5", "陈小雨", "20260203", "C32", "三年级(2)班",
                "R2", "翠湖天地", "陈母", "13800000005", "chenma", false, ""));

        // ---------- 当天乘车事实 ----------
        for (Student s : store.listStudents()) {
            store.saveRide(new RideStatus(
                    s.id() + ":" + date, date, s.id(), s.classId(), s.routeId(), s.defaultStop(),
                    "PLANNED", "PLANNED", "NONE", null, null, null, null,
                    false, "", null, null, null, null, null, null, now, 1));
        }
    }
}
