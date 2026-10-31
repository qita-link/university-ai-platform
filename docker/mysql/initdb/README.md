# initdb 目录

这里的 `.sql` / `.sh` 只会在 **MySQL 数据卷为空**（第一次启动）时执行一次，按文件名排序。

## 当前内容

- `10-campus_ai.sql` —— 本机导出的数据（**通用版**，不含 `CREATE DATABASE` / `USE`），
  所以导入到哪个库由 `.env.docker` 的 `DB_NAME` 决定。

该文件已在 `.gitignore` 中忽略，不进版本库。

## 换成自己的数据

```bash
rm -f docker/mysql/initdb/*.sql
cp /path/to/dump.sql docker/mysql/initdb/10-campus_ai.sql
docker compose --env-file .env.docker down -v   # 必须清卷，否则不会重跑
bash scripts/docker-up.sh
```

导出的 SQL 建议用 `--no-create-db`（不含建库语句），否则会无视 `DB_NAME`：

```bash
mysqldump --single-transaction --routines --triggers --no-tablespaces \
  --default-character-set=utf8mb4 --no-create-db campus_ai > dump.sql
```

## 不想要初始数据

清空本目录即可，应用启动时由 Flyway 自动建表（V1–V9），
管理员账号用 `.env.docker` 的 `APP_BOOTSTRAP_ADMIN_*` 创建。
