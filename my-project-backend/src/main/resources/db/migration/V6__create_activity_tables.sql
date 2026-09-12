-- 抢活动模块：线下活动限量名额报名。
-- db_activity 活动主表（total_stock 名额 / grabbed 已抢，行锁兜底防超卖）；
-- db_activity_order 报名单（activity_id+uid 唯一键兜底幂等，status 为异步落单结果）。

CREATE TABLE IF NOT EXISTS db_activity (
    id INT NOT NULL AUTO_INCREMENT,
    title VARCHAR(50) NOT NULL,
    description VARCHAR(500) NOT NULL,
    location VARCHAR(100) NOT NULL,
    activity_time DATETIME NOT NULL,
    total_stock INT NOT NULL,
    grabbed INT NOT NULL DEFAULT 0,
    grab_start_time DATETIME NOT NULL,
    grab_end_time DATETIME NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS db_activity_order (
    id INT NOT NULL AUTO_INCREMENT,
    activity_id INT NOT NULL,
    uid INT NOT NULL,
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0=排队中 1=报名成功 2=报名失败',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_activity_order (activity_id, uid),
    CONSTRAINT fk_activity_order_activity FOREIGN KEY (activity_id)
        REFERENCES db_activity (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 种子数据：一个报名进行中、一个已抢完、一个未开始（时间基于部署时刻动态生成，便于本地联调）
INSERT INTO db_activity (title, description, location, activity_time, total_stock, grabbed, grab_start_time, grab_end_time)
VALUES
    ('校园技术沙龙 · 第 12 期', '后端并发与检索主题分享，现场答疑，限 20 个名额。', '大学生活动中心 302', DATE_ADD(NOW(), INTERVAL 7 DAY), 20, 3, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 6 DAY)),
    ('夜跑团 · 环湖 5 公里', '本周五晚环校夜跑，限量 2 个纪念名额（已抢完演示）。', '东湖环湖步道南门', DATE_ADD(NOW(), INTERVAL 3 DAY), 2, 2, DATE_SUB(NOW(), INTERVAL 2 DAY), DATE_ADD(NOW(), INTERVAL 2 DAY)),
    ('毕业季跳蚤市场摊位', '下周开放报名，限量 30 个摊位（未开始演示）。', '南广场', DATE_ADD(NOW(), INTERVAL 14 DAY), 30, 0, DATE_ADD(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 10 DAY));
