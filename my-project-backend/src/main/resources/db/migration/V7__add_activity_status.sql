-- 活动上下架：管理端可下架活动（用户侧列表与报名立即不可见），1=上架 0=下架。
-- V6 种子数据与既有活动默认上架。

ALTER TABLE db_activity
    ADD COLUMN status TINYINT NOT NULL DEFAULT 1 COMMENT '1=上架 0=下架';
