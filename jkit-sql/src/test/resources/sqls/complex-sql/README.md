# 复杂业务 SQL 300 条 · 数据模型与使用说明

四份 SQL 文件覆盖 **MySQL 8.0+ / PostgreSQL 12+ / Oracle 12c+ / SQL Server 2017+** 四种主流数据库，每条 SQL 均按对应方言独立渲染并通过语法校验。

| 文件 | 数据库 | 版本 | 大小 | 说明 |
|---|---|---|---|---|
| `mysql_complex_300.sql` | MySQL | 8.0+ | ~470 KB | 使用 `GROUP_CONCAT`、`DATE_FORMAT`、`LIMIT` 等 MySQL 语法 |
| `postgresql_complex_300.sql` | PostgreSQL | 12+ | ~470 KB | 使用 `STRING_AGG`、`DATE_TRUNC`、`EXTRACT`、标准窗口帧 |
| `oracle_complex_300.sql` | Oracle | 12c+ | ~470 KB | 使用 `LISTAGG`、`TRUNC`、`MONTHS_BETWEEN`、`FETCH FIRST` |
| `sqlserver_complex_300.sql` | SQL Server | 2017+ | ~475 KB | 使用 `STRING_AGG`、`DATEDIFF`、`OFFSET/FETCH`，每条以 `GO` 结束 |
| `mysql_init.sql` | MySQL | 8.0+ | ~9.6 MB | 建表 + 测试数据初始化（含 `DROP TABLE IF EXISTS` 幂等重建） |
| `postgresql_init.sql` | PostgreSQL | 12+ | ~11 MB | 建表 + 测试数据初始化 |
| `oracle_init.sql` | Oracle | 12c+ | ~19 MB | 建表 + 测试数据初始化（PL/SQL `BEGIN…EXCEPTION` 幂等重建） |
| `sqlserver_init.sql` | SQL Server | 2017+ | ~11 MB | 建表 + 测试数据初始化（批处理 `GO` 分隔） |

---

## 一、初始化脚本（建表 + 测试数据）

四份 `*_init.sql` 为 300 条复杂 SQL 提供可运行的配套环境：**50 张表的建表语句 + 约 8 万行带业务特征的测试数据**。先在目标库执行初始化，再执行对应的 `*_complex_300.sql` 即可复现全部分析。

数据生成的设计要点（目标是"能真实跑出结果"而非仅语法通过）：

- 数据量向近期倾斜（约 45% 订单落在近 90 天），保证 30/90 天窗口类查询有足量样本；
- 客户 / 商品按帕累托分布生成，长尾滞销、低库存缺货等边缘场景被刻意构造；
- 注入风控特征样本：短时高频交易、拆分交易（4.6~4.95 万 ×5 笔）、快进快出、循环对敲转账、资金链路枢纽、境外聚集消费、套现嫌疑；
- 人力域注入连续迟到 / 缺勤孤岛、组织架构递归树、薪酬带宽分位样本；
- 日期以各库"当前日期"函数为基准生成（`NOW()` / `SYSDATE` / `GETDATE()`），因此同一份数据在不同时间导入都能与"相对当前日期"的 SQL 对齐。

执行顺序（以 PostgreSQL 为例，其余库替换为对应客户端即可）：

```bash
# 1) 建库并导入初始化（建表 + 数据）
psql -U <user> -h <host> -d bench -f postgresql_init.sql

# 2) 导入并运行 300 条复杂 SQL（可整文件执行）
psql -U <user> -h <host> -d bench -f postgresql_complex_300.sql
```

- **MySQL**：`mysql -u <user> -p <db> --default-character-set=utf8mb4 < mysql_init.sql`
- **Oracle**：SQL\*Plus 中 `@oracle_init.sql`（脚本含 `DROP TABLE` 幂等清理，首次执行会出现"表不存在"的忽略提示，属正常）
- **SQL Server**：`sqlcmd -S <server> -U <user> -d <db> -i sqlserver_init.sql`

幂等性：脚本开头清理已存在表——MySQL / PostgreSQL 用 `DROP TABLE IF EXISTS`；Oracle 用 `BEGIN EXECUTE IMMEDIATE ... EXCEPTION`；SQL Server 用 `IF OBJECT_ID(...) IS NOT NULL DROP TABLE`——可重复执行。

---

## 二、内容结构

300 条按业务域分布：

| 业务域 | 编号区间 | 条数 | 典型分析主题 |
|---|---|---|---|
| 电商 / 零售 | 001 – 110 | 110 | GMV 趋势、RFM、Cohort 留存、漏斗、购物篮、ABC 帕累托、库存周转、物流时效、支付风控、门店效能 |
| 金融 / 财务 | 111 – 210 | 100 | 反洗钱、可疑交易、ECL 三阶段、RWA、NPL 迁徙、拨备、杜邦分析、NIM、基金净值/回撤/夏普、外汇敞口 |
| 人力 / 组织 | 211 – 285 | 75 | 组织架构递归树、管理幅度、薪酬带宽分位、Comp-Ratio、考勤孤岛、离职风险、招聘漏斗、继任梯队 |
| 数据治理 | 286 – 289 | 4 | 缺失值检测、重复记录识别、引用完整性、订单支付对账 |
| 跨域综合 | 290 – 300 | 11 | 客户跨域价值、门店人效、分支行对比、多维下钻、异常波动检测、同比环比、滚动窗口、KPI 看板 |

SQL 复杂度特征（全部 300 条均至少满足其中 3 项以上）：

- 多层 CTE（多数 3 – 6 层），部分含递归 CTE
- 窗口函数：`ROW_NUMBER / RANK / DENSE_RANK / NTILE / LAG / LEAD / FIRST_VALUE / PERCENT_RANK / CUME_DIST`，以及 `ROWS / RANGE` 窗口帧下的累计、移动平均、滚动求和
- 条件聚合（`SUM(CASE WHEN ...)`）、自关联、相关子查询、集合运算（`UNION ALL`）
- 业务建模：间隙与孤岛（island-and-gap）识别、同比/环比/滚动 12 月、Z-score 异常检测、帕累托累计占比、分位数分层

---

## 三、数据模型总览

模型为**一套跨业务域的统一示例模型**，共 50 张表。四份 SQL 文件引用的是同一套逻辑模型，字段名在各库中保持一致，仅日期函数、字符串聚合、分页等语法按方言转换。

### 2.1 电商 / 零售域（21 张表）

```
categories ─┬─< products ──< order_items >── orders >── customers
            │                                  ├─< payments
            │                                  ├─< refunds
            │                                  ├─< shipments ──< logistics_nodes
            │                                  └─< order_coupon >── coupons
stores ─────┘        warehouses ──< inventory >── products
   └─< store_targets      └─< wh_stock / wh_sales
customers ──< carts / user_events / reviews / cust_level_log
promotions ──< order_coupon
```

| 表名 | 主键 | 主要字段 | 说明 |
|---|---|---|---|
| `customers` | customer_id | customer_name, gender, birth_date, city, province, register_date, level, status, last_order_date, order_cnt, total_amt | 电商客户主表 |
| `categories` | category_id | category_name, parent_category_id | 商品品类（支持二级） |
| `products` | product_id | product_name, category_id, brand, price, cost, launch_date, status | 商品 |
| `stores` | store_id | store_name, city, province, region, open_date, status, dept_id | 门店（dept_id 关联人力域部门） |
| `store_targets` | store_id + target_month | target_amt | 门店月度销售目标 |
| `orders` | order_id | customer_id, store_id, order_date, status, channel, pay_amount, discount_amount | 订单主表 |
| `order_items` | order_id + product_id | quantity, amount, price, pay_amount, sku_cnt, cat_cnt | 订单明细 |
| `payments` | payment_id | order_id, pay_method, pay_amount, pay_date, pay_time, status | 支付流水 |
| `refunds` | refund_id | order_id, refund_amount, refund_reason, reason, refund_date, status | 退款 |
| `shipments` | shipment_id | order_id, carrier, ship_date, delivery_days, province, city, status, last_node, last_node_time | 物流运单 |
| `logistics_nodes` | node_id | order_id, node_name, node_time | 物流轨迹节点 |
| `warehouses` | warehouse_id | warehouse_name, city, province, region | 仓库 |
| `inventory` | product_id + warehouse_id | stock_qty, quantity, safety_stock, safety_qty | 库存 |
| `wh_stock` | warehouse_id + product_id | stock_qty, safety_stock, safety_qty, sku_cnt | 仓库库存快照 |
| `wh_sales` | warehouse_id + product_id | qty_sold, amt_sold, sale_date | 仓库出库流水 |
| `carts` | cart_id | customer_id, product_id, add_time, quantity, status | 购物车 |
| `coupons` | coupon_id | coupon_name, coupon_type, promo_id, face_value, issued_cnt, used_cnt, start_date, end_date, status | 优惠券 |
| `order_coupon` | order_id + coupon_id | promo_id, coupon_cnt | 订单用券 |
| `promotions` | promo_id | promo_name, promo_type, category_id, budget, start_date, end_date, discount_rate | 促销活动 |
| `reviews` | review_id | order_id, product_id, customer_id, rating, content, review_date | 商品评价 |
| `user_events` | event_id | customer_id, session_id, event_type, event_time, product_id, page, device | 用户行为埋点 |
| `cust_level_log` | log_id | customer_id, old_level, new_level, change_date, status | 会员等级变更 |

### 2.2 金融 / 财务域（16 张表）

```
branches ──< accounts >── fin_customers ──< loans ──< repayments
   │           └─< transactions              ├─< deposits
   │        cards ──< card_txns              └─< holdings >── fund_products ──< fund_nav
   └─< fin_reports                    credit_scores / risk_events
                                      fx_rates
```

| 表名 | 主键 | 主要字段 | 说明 |
|---|---|---|---|
| `fin_customers` | cust_id | cust_name, cust_type, gender, birth_date, city, province, register_date, risk_level, branch_id, manager_id, status | 金融客户 |
| `branches` | branch_id | branch_name, city, region, open_date, manager_id | 分支行 |
| `accounts` | account_id | cust_id, branch_id, account_type, balance, acct_balance, open_date, status, currency | 账户 |
| `transactions` | txn_id | account_id, cust_id, txn_date, txn_type, amount, channel, counterparty, merchant, mcc, is_overseas, city, card_id | 交易流水 |
| `cards` | card_id | cust_id, account_id, card_type, credit_limit, open_date, status | 银行卡 |
| `card_txns` | txn_id | card_id, txn_date, amount, merchant, mcc, is_overseas, city, status | 卡交易 |
| `loans` | loan_id | cust_id, branch_id, product_type, loan_amount, interest_rate, term_months, start_date, end_date, maturity_date, status, overdue_days, outstanding | 贷款 |
| `repayments` | repayment_id | repay_id, loan_id, repay_date, due_date, amount, due_amount, paid_amount, principal, interest, status, overdue_days | 还款流水 |
| `deposits` | deposit_id | cust_id, account_id, deposit_amt, dep_amt, amount, rate, avg_rate, interest_rate, term_months, start_date, maturity_date, status | 存款 |
| `credit_scores` | score_id | cust_id, score, score_date, model_version | 信用评分 |
| `risk_events` | event_id | cust_id, event_type, risk_level, risk_num, event_date, amount, status | 风险事件 |
| `fund_products` | fund_id | fund_name, fund_type, manager_id, manager_name, launch_date, risk_level, status | 基金产品 |
| `fund_nav` | fund_id + nav_date | nav, cur_nav, acc_nav | 基金净值 |
| `holdings` | holding_id | hold_id, cust_id, fund_id, shares, cost_amount, market_value, purchase_date | 基金持仓 |
| `fx_rates` | rate_date + currency | rate, base_currency | 汇率 |
| `fin_reports` | report_id | branch_id, period, report_type, subject, amount, asset, liability, revenue, cost, profit, loan_amt, deposit_amt | 财务报表 |

### 2.3 人力 / 组织域（13 张表）

```
departments ──< employees ──< salaries / attendance / leaves / performance
   (自关联)        │            emp_changes / training_records / payroll
                   └─< project_assignments >── projects
   recruitment（按 dept_id 归口）
```

| 表名 | 主键 | 主要字段 | 说明 |
|---|---|---|---|
| `departments` | dept_id | dept_name, parent_dept_id, cost_center, budget_cost | 部门（自关联树） |
| `employees` | emp_id | emp_name, gender, birth_date, dept_id, manager_id, position, job_level, salary, hire_date, leave_date, status, city | 员工 |
| `salaries` | salary_id | emp_id, base_salary, bonus, allowance, total_salary, effective_date, pay_month | 薪酬记录（多次调薪留痕） |
| `attendance` | att_id | emp_id, att_date, check_in, check_out, work_hours, overtime_hours, status | 考勤日明细 |
| `leaves` | leave_id | emp_id, leave_type, start_date, end_date, days, status | 休假记录 |
| `leave_requests` | req_id | emp_id, leave_type, start_date, end_date, days, status, apply_date | 请假申请 |
| `performance` | perf_id | emp_id, period, score, rating, review_date | 绩效考核 |
| `emp_changes` | change_id | emp_id, change_type, change_date, old_dept_id, new_dept_id, old_salary, new_salary, old_position, new_position | 人事异动 |
| `training_records` | record_id | emp_id, course_name, train_date, hours, score, cost, status | 培训记录 |
| `recruitment` | req_id | dept_id, position, headcount, status, channel, open_date, close_date, apply_cnt, offer_cnt, hired_cnt, offer_salary, channel_cost | 招聘需求 |
| `projects` | project_id | project_name, dept_id, start_date, end_date, budget, cost, revenue, status | 项目 |
| `project_assignments` | assign_id | project_id, emp_id, allocation_pct, role | 项目成员投入 |
| `payroll` | payroll_id | emp_id, dept_id, pay_month, pay_date, gross_pay, net_pay, tax, social_insurance, pay_amount, pay_method | 工资单 |

---

## 四、方言差异对照

四份文件由同一套基准 SQL 渲染生成，以下差异已全部处理：

| 能力 | MySQL | PostgreSQL | Oracle | SQL Server |
|---|---|---|---|---|
| 限制行数 | `LIMIT n` | `LIMIT n` | `FETCH FIRST n ROWS ONLY` | `OFFSET 0 ROWS FETCH NEXT n ROWS ONLY` |
| 字符串聚合 | `GROUP_CONCAT(... SEPARATOR ',')` | `STRING_AGG(x, ',' ORDER BY y)` | `LISTAGG(x, ',') WITHIN GROUP (ORDER BY y)` | `STRING_AGG(x, ',') WITHIN GROUP (ORDER BY y)` |
| 日期截断 | `DATE_FORMAT` / `DATE(...)` | `DATE_TRUNC('month', d)` | `TRUNC(d, 'MM')` | `DATEFROMPARTS(YEAR(d), MONTH(d), 1)` |
| 日期加减 | `DATE_SUB/ DATE_ADD(d, INTERVAL n DAY)` | `d - INTERVAL 'n days'` | `d - INTERVAL 'n' DAY` | `DATEADD(day, -n, d)` |
| 日期差（天） | `DATEDIFF(b, a)` | `b::date - a::date` | `b - a` | `DATEDIFF(day, a, b)` |
| 日期差（月） | `PERIOD_DIFF` | 年差×12 + 月差 | `MONTHS_BETWEEN(b, a)` | `DATEDIFF(month, a, b)` |
| 格式化 | `DATE_FORMAT(d, '%Y-%m')` | `TO_CHAR(d, 'YYYY-MM')` | `TO_CHAR(d, 'YYYY-MM')` | `FORMAT(d, 'yyyy-MM')` |
| 当前日期 | `CURRENT_DATE` | `CURRENT_DATE` | `TRUNC(SYSDATE)` | `CAST(GETDATE() AS DATE)` |
| 递归 CTE | `WITH RECURSIVE x AS` | `WITH RECURSIVE x AS` | `WITH x(col) AS` | `WITH x AS` |
| 空表查询 | 省略 FROM | 省略 FROM | `FROM dual` | 省略 FROM |
| 字符串长度 | `LENGTH(s)` | `LENGTH(s)` | `LENGTH(s)` | `LEN(s)` |
| 子串截取 | `SUBSTR(s, a, b)` | `SUBSTR(s, a, b)` | `SUBSTR(s, a, b)` | `SUBSTRING(s, a, b)` |
| 子串位置 | `INSTR(s, sub)` | `POSITION(sub IN s)` | `INSTR(s, sub)` | `CHARINDEX(sub, s)` |
| 转字符串 | `CAST(x AS CHAR(n))` | `CAST(x AS VARCHAR(n))` | `CAST(x AS VARCHAR(n))` | `CAST(x AS VARCHAR(n))` |
| 取模 | `MOD(a, b)` | `MOD(a, b)` | `MOD(a, b)` | `a % b` |
| `LEAST / GREATEST` | 原生支持 | 原生支持 | 原生支持 | 改写为 `CASE WHEN` |
| 样本标准差 | `STDDEV_SAMP(x)` | `STDDEV_SAMP(x)` | `STDDEV_SAMP(x)` | `STDEV(x)` |
| 相关系数 | 展开公式实现 | `CORR(x, y)` | `CORR(x, y)` | 展开公式实现 |
| 日期字面量 | `CAST('2024-01-01' AS DATE)` | `DATE '2024-01-01'` | `DATE '2024-01-01'` | `CAST('2024-01-01' AS DATE)` |

> 说明：`CORR` 在 MySQL 与 SQL Server 中无内置函数，已统一改为皮尔逊相关系数的聚合展开式：
> `(n·Σxy − Σx·Σy) / SQRT((n·Σx² − (Σx)²)·(n·Σy² − (Σy)²))`

---

## 五、使用建议

1. **直接执行**：每条 SQL 用 `-- [编号] 分类 | 业务域 | 描述` 注释分隔，可整段单独选中执行；SQL Server 版本每条以 `GO` 结尾，支持整文件批量执行。
2. **时间范围**：SQL 以各库"当前日期"函数（如 `CURRENT_DATE` / `SYSDATE` / `GETDATE()`）为基准回溯 30 天 – 3 年，如需固定区间，把 `当前日期 - INTERVAL '365 days'` 一类表达式替换为具体日期即可。
3. **性能提示**：示例 SQL 以表达业务逻辑与分析模型为第一目标，未针对具体数据量做索引与执行计划优化；在生产大表上运行前建议先加时间分区过滤与必要索引。
4. **结果集限制**：部分排名类查询末尾带 `LIMIT 200 / 300`（Oracle 为 `FETCH FIRST`，SQL Server 为 `OFFSET/FETCH`），可按需调整或删除。
5. **字段名一致性**：四份文件字段名完全一致，便于跨库对比同名指标的写法差异。

---

## 六、分析主题速查

| 主题 | 代表编号 |
|---|---|
| 窗口函数排名 / 分组 TopN | 001, 013, 091, 184 |
| 同比 / 环比 / 移动平均 | 002, 004, 007, 295, 296 |
| RFM 与客户价值分层 | 031, 074, 124, 290 |
| Cohort 留存 | 032, 059, 142, 221 |
| 漏斗转化 | 023, 052, 099, 261 |
| 帕累托 / ABC | 006, 120, 297 |
| 间隙与孤岛（连续行为） | 021, 243 |
| 递归 CTE（组织树 / 日期补全） | 022, 211, 213 |
| 反洗钱 / 可疑交易 | 117, 129, 130, 131, 138 |
| 信贷风险与拨备 | 161, 162, 164, 174, 175 |
| 基金净值 / 回撤 / 夏普 | 181, 182, 183, 185 |
| 财务分析（杜邦、NIM、预算） | 196, 198, 200, 150 |
| 薪酬带宽 / Comp-Ratio / 同工同酬 | 228, 229, 230 |
| 招聘与继任 | 255, 261, 263, 272, 283 |
| 数据质量与主数据 | 286, 287, 288, 289 |
| 综合看板 | 109, 208, 210, 260, 285, 299, 300 |

---

## 七、校验说明

- **语法校验**：四份查询文件（各 300 条，共 1200 条）与四份初始化脚本，全部通过 **sqlglot 30.x** 对应方言的 `parse` 校验，0 语法错误。
- **真实执行**（沙箱已安装真实引擎）：
  - **PostgreSQL 16** 与 **MySQL 8**：导入对应初始化脚本后逐条执行 300 条复杂 SQL，**执行失败 0 条，返回 0 行的查询 0 条**——300 条全部跑出非空结果，覆盖窗口函数、递归 CTE、集合运算、风控 / 留存 / 帕累托 / 考勤孤岛等业务模型。
  - **Oracle / SQL Server**：沙箱无法安装真实引擎，初始化脚本与查询文件已通过 sqlglot 逐方言语法校验；其中 Oracle 的 `BEGIN … EXCEPTION` PL/SQL 块与 SQL Server 的 `GO` 批处理分隔符均为对应库原生合法语法。
