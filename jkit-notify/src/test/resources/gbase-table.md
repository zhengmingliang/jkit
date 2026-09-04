### 查询某张表的数据节点存储情况

### GBase集群中查看表数据分布的方法

在GBBase集群数据库中，查看某个表的数据分布情况（即数据具体存储在哪些数据节点上）主要通过查询系统表来实现。GBase使用分布式架构，数据会根据分布策略（如随机分布、hash分布或复制表）分散在多个数据节点（DataNode）上。以下方法基于GBase的系统表和内置命令，能准确获取表的分布信息。

#### 1. **使用SQL查询系统表（推荐方法）**
   这是最直接的方式，通过查询`information_schema.CLUSTER_TABLE_SEGMENTS`系统表，可以获取指定表在数据节点上的详细分布信息。该表包含每个表分片（segment）的存储位置、数据大小等关键字段。
   
   - **SQL语句示例**：
     ```sql
     SELECT * 
     FROM information_schema.CLUSTER_TABLE_SEGMENTS 
     WHERE table_schema = '您的数据库名' AND table_name = '您的表名';
     ```
     - 替换`您的数据库名`和`您的表名`为实际值（例如，`table_schema='tpch'`和`table_name='lineitem'`）。
     - **输出字段解释**：
       - `HOST`：数据节点的IP地址，显示数据具体存储在哪个节点上。
       - `TABLE_DATA_SIZE`：表分片的实际数据大小（注意：GBase默认压缩，此值可能小于原始文本文件大小）。
       - `TABLE_STORAGE_SIZE`：表分片的存储空间占用（包括压缩和元数据）。
       - `DATA_PERCENT`：该分片在表总数据中的占比。
       - 其他字段如`SUFFIX`和`TABLE_VC`表示分片标识和虚拟集群信息。
     - **示例输出**（基于搜索结果）：
       | TABLE_VC | TABLE_SCHEMA | TABLE_NAME | SUFFIX | HOST       | TABLE_DATA_SIZE | TABLE_STORAGE_SIZE | DATA_PERCENT |
       |----------|--------------|------------|--------|------------|-----------------|--------------------|--------------|
       | vc1      | test         | t1         | n1     | 10.0.0.27  | 116             | 1428               | 50.1581%     |
       | vc1      | test         | t1         | n2     | 10.0.0.26  | 116             | 1419               | 49.8419%     |
       
       此输出表示表`t1`的数据分片存储在节点`10.0.0.27`和`10.0.0.26`上，分别占50.1581%、49.8419%。

   - **注意事项**：
     - 此查询仅显示主分片数据（不包括备份数据）。对于复制表（replicated table），所有节点都会存储完整数据，查询结果会列出所有节点。
     - 数据大小受压缩影响：`TABLE_DATA_SIZE`和`TABLE_STORAGE_SIZE`通常小于原始文件大小（如文本格式），这是GBase的默认优化。[citation:1]

#### 2. **使用gcadmin命令（集群级别，辅助参考）**
   如果需要快速查看集群整体的数据分布规则（而非特定表），可以使用`gcadmin`命令。但这更适合集群管理，不能直接定位到具体表的节点分布。
   
   - **命令示例**：
     ```bash
     gcadmin showdistribution
     ```
     - 此命令显示集群的数据分布策略（如hash分布或随机分布），但不提供表级别细节。
     - 若要查看节点状态，可结合`gcadmin`的其他子命令（如`gcadmin showstate`），但它无法替代SQL查询的精确性。

#### 3. **其他相关方法**
   - **查看表的总数据量**：如果还需了解表的总体存储情况，可查询`information_schema.cluster_tables`：
     ```sql
     SELECT * 
     FROM information_schema.cluster_tables 
     WHERE table_schema = '您的数据库名' AND table_name = '您的表名';
     ```
     此表提供表的总数据量（包括主分片和备份分片），但不会显示具体节点分布。
     **实例输出**
   ```
TABLE_VC    |TABLE_SCHEMA|TABLE_NAME             |CREATE_TIME            |UPDATE_TIME            |TABLE_DATA_SIZE|TABLE_STORAGE_SIZE|TABLE_TYPE|
------------+------------+-----------------------+-----------------------+-----------------------+---------------+------------------+----------+
vcname000001|test_db     |tmp_test24_svg0_3_kh5ee|2025-08-18 15:16:02.000|2025-08-18 15:16:02.000|            106|              1085|random    |
   ```
   - **理解分布策略**：GBase支持三种分布方式：
     - **hash分布**：数据按hash键值计算后固定分配到节点。
     - **随机分布**：数据随机分散到所有节点。
     - **复制表**：所有节点存储完整数据副本（查询时会列出所有节点）。
     在执行上述SQL前，建议先确认表的分布策略（可通过`gbase.table_distribution`系统表查询）。

```sql
SELECT * 
FROM information_schema.CLUSTER_TABLE_SEGMENTS 
WHERE table_schema = 'eoai' AND table_name = 'fct_agt_savinf';
```
查询结果：
```
TABLE_VC    |TABLE_SCHEMA|TABLE_NAME    |SUFFIX|HOST         |TABLE_DATA_SIZE|TABLE_STORAGE_SIZE|DATA_PERCENT|
------------+------------+--------------+------+-------------+---------------+------------------+------------+
vcname000001|eoai        |fct_agt_savinf|n1    |172.16.18.227|           1428|              4454|50.1581%    |
vcname000001|eoai        |fct_agt_savinf|n2    |172.16.18.226|           1419|              4419|49.8419%    |
```
