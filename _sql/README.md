# 数据库脚本说明

## 目录结构

```text
_sql/
├── database-er-diagram.html          # 可视化 ER 图
├── mysql/
│   ├── free-fs.sql                   # MySQL 全新安装基线
│   ├── legacy/
│   │   └── free-fs-2.2.sql           # 工作空间改造前的旧结构（仅存档）
│   └── migrations/                   # MySQL 增量升级脚本
└── postgresql/
    ├── free-fs_pg.sql                # PostgreSQL 全新安装基线
    └── migrations/                   # PostgreSQL 增量升级脚本
```

## 全新安装

MySQL：

```bash
mysql -u root -p -e 'CREATE DATABASE IF NOT EXISTS `free-fs` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci'
mysql -u root -p free-fs < _sql/mysql/free-fs.sql
```

PostgreSQL：

```bash
psql -U postgres -c "CREATE DATABASE free-fs;"
psql -U postgres -d free-fs -f _sql/postgresql/free-fs_pg.sql
```

基线脚本包含 `DROP TABLE IF EXISTS`，只适用于空库或全新环境，**禁止对已有数据的库直接执行**。脚本会写入默认管理员、默认工作空间、内置角色与权限，以及常用存储平台等种子数据；运行时产生的文件、传输、分享、登录和操作日志记录不会写入。

默认账号：`admin / admin`。首次登录后请立即修改密码。

## 升级

全新安装的基线已经包含下列全部变更，**不需要再执行 `migrations/`**。仓库没有引入 Flyway/Liquibase 等迁移框架，已有数据库升级时需要手动按文件名顺序执行对应数据库的 `migrations/` 脚本（源库需已包含工作空间结构）：

```bash
# MySQL
mysql -u root -p free-fs < _sql/mysql/migrations/20260718_storage_dedup.sql
# ... 按文件名顺序继续执行其余脚本

# PostgreSQL
psql -U postgres -d free-fs -f _sql/postgresql/migrations/20260718_storage_dedup.sql
# ... 按文件名顺序继续执行其余脚本
```

执行前请先备份数据库，并在测试环境验证。

`legacy/free-fs-2.2.sql` 仅用于查阅旧结构，不代表受支持的升级路径。工作空间改造之前的版本（约 2.2.x）没有自动升级脚本；如需保留数据，应导出旧数据后导入到按当前基线创建的新库，并根据业务需要规划用户到工作空间的映射。

## Docker

`docker/docker-compose.yml` 只在 MySQL 数据卷首次创建时执行 `mysql/free-fs.sql`，数据目录为 `docker/mysql/data`。如果该目录中已有旧数据，需先备份并删除后重新启动，否则容器会继续使用旧结构。

## 表关系

DDL 中没有声明物理外键，表之间均为逻辑引用（字段 + 索引）。完整的表、字段与引用关系见 [database-er-diagram.html](database-er-diagram.html)。
