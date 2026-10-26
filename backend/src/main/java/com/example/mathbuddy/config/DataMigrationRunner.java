package com.example.mathbuddy.config;

import com.example.mathbuddy.service.MistakeBook;
import com.example.mathbuddy.service.UserStore;
import com.example.mathbuddy.service.WeaknessTracker;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动后确保演示账号存在，并把旧版全局数据划归最早创建的账号。
 * 放在 ApplicationRunner 中，保证三个 Store 的 @PostConstruct 已先完成。
 */
@Component
public class DataMigrationRunner implements ApplicationRunner {

    private final UserStore userStore;
    private final MistakeBook mistakeBook;
    private final WeaknessTracker weaknessTracker;

    public DataMigrationRunner(UserStore userStore, MistakeBook mistakeBook, WeaknessTracker weaknessTracker) {
        this.userStore = userStore;
        this.mistakeBook = mistakeBook;
        this.weaknessTracker = weaknessTracker;
    }

    @Override
    public void run(ApplicationArguments args) {
        UserStore.User first = userStore.ensureBootstrapUser();
        if (first == null) {
            return;
        }
        mistakeBook.claimLegacy(first.id());
        weaknessTracker.claimLegacy(first.id());
    }
}
