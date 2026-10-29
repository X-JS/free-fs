-- file_info.is_dir / is_deleted 由 smallint 改为 boolean
--
-- 原因：实体 FileInfo 的 isDir、isDeleted 是 Boolean，QueryWrapper 直接用
-- eq(true) / eq(false) 传参，pgjdbc 会以 boolean 类型发送；
-- 而 PostgreSQL 不存在 smallint = boolean 的隐式转换，会报：
--   ERROR: operator does not exist: smallint = boolean
-- 改为 boolean 后，插入、更新、条件查询都与 Java 的 Boolean 类型一致。
--
-- 已有库执行本脚本；全新初始化直接使用最新的 free-fs_pg.sql 即可，无需执行。

ALTER TABLE "file_info"
    ALTER COLUMN "is_dir" TYPE boolean USING ("is_dir" <> 0),
    ALTER COLUMN "is_deleted" TYPE boolean USING ("is_deleted" <> 0);
