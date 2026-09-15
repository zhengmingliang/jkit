-- ==============================================================================
-- Oracle 复杂业务 SQL 300 条
-- 适用版本：Oracle 12c+
-- ==============================================================================
--
-- 内容说明：
--   1. 覆盖电商/零售、金融/财务、人力/组织三大业务域，另含数据治理与跨域综合分析。
--   2. 每条 SQL 均可独立执行，普遍使用 CTE、窗口函数（排名/位移/分箱/累计/移动平均）、
--      递归 CTE、条件聚合、自关联、相关子查询、集合运算等复杂写法。
--   3. 业务模型涵盖 RFM、Cohort 留存、漏斗转化、帕累托/ABC、购物篮、
--      反洗钱与可疑交易、ECL 三阶段、RWA、杜邦分析、组织架构树、
--      薪酬带宽与分位、考勤孤岛识别、主数据质量校验等。
--   4. 表结构与字段定义见同目录 README.md（数据模型说明）。
--   5. 日期以各库当前日期函数为基准，实际使用可替换为固定日期。
--
-- ==============================================================================
-- 目录
-- ==============================================================================
--
-- 【电商】110 条
--   001  窗口函数·分组排名  -  各城市销售额 Top3 商品及城市内占比
--   002  窗口函数·环比计算  -  月度 GMV 及环比增长率
--   003  窗口函数·累计求和  -  门店累计销售额与年度目标达成率
--   004  窗口函数·移动平均  -  近 7 日移动平均 GMV 与趋势判断
--   005  窗口函数·占比分析  -  各渠道 GMV 及其在总盘中占比
--   006  窗口函数·帕累托分析  -  商品 ABC 分类（累计销售占比 80/95/100）
--   007  自连接·同比分析  -  品类销售额同比（YoY）分析
--   008  条件聚合·交叉矩阵  -  品类 × 渠道 销售交叉矩阵
--   009  CTE·新老客识别  -  新老客户销售贡献对比
--   010  窗口函数·留存计算  -  月度复购率与人均购买次数
--   011  分位数·NTILE  -  订单金额分层（五等分）与层均价值
--   012  排名·门店人效  -  门店销售排名与区域内份额
--   013  窗口函数·排名变化  -  商品销售排名环比变化（上升/下降）
--   014  多表聚合·退款分析  -  各品类退款率与退款原因分布
--   015  多表关联·营销效果  -  优惠券核销率与营销 ROI
--   016  条件聚合·客单价分层  -  客单价分布与城市消费力分层
--   017  时间维度·小时分析  -  分时段销售热力（按小时 + 工作日/周末）
--   018  自连接·购物篮分析  -  跨品类连带购买（品类对共现）分析
--   019  条件聚合·促销对比  -  促销期与非促销期销售对比
--   020  分桶·履约时效  -  订单履约时效分布（下单到签收）
--   021  间隙与孤岛·连续行为  -  连续下单用户识别（连续 N 天有订单）
--   022  递归CTE·日期补全  -  生成完整日期序列并补全缺失销售日
--   023  漏斗分析·转化  -  浏览-加购-下单-支付全链路转化漏斗
--   024  窗口函数·LEAD  -  用户首单到二单的时间间隔分布
--   025  多表·库存周转  -  商品库存周转天数与安全库存预警
--   026  左连接·滞销识别  -  滞销商品识别（上架后长期无销量）
--   027  时间分段·生命周期  -  商品生命周期阶段划分（新品/成长/成熟/衰退）
--   028  统计·相关性  -  商品评分区间与销量/价格相关性分析
--   029  窗口·缺货损失  -  低库存缺货损失估算（按日销量外推）
--   030  漏斗·购物车  -  购物车加购-支付转化与放弃分析
--   031  RFM模型  -  用户 RFM 三维评分与价值分层
--   032  Cohort留存  -  用户注册月度 Cohort 留存矩阵
--   033  窗口·流失预警  -  用户活跃度衰减与流失预警名单
--   034  累计·LTV  -  用户生命周期价值（LTV）与价值分层
--   035  二八法则  -  高价值用户识别与销售集中度分析
--   036  聚合·品类广度  -  用户购买品类广度与交叉销售机会
--   037  状态转移·等级迁移  -  会员等级迁移矩阵（年初 vs 当前）
--   038  时间窗口·唤醒  -  沉睡用户识别与唤醒效果评估
--   039  FIRST_VALUE·归因  -  用户首单渠道归因与后续渠道偏好
--   040  LEAD·路径分析  -  用户品类购买路径（下一个购买品类）流转
--   041  HAVING·跨店行为  -  跨门店/跨城市购买用户识别
--   042  日期函数·营销名单  -  生日当月营销名单与偏好品类推荐
--   043  异常检测·统计  -  订单金额异常检测（偏离个人历史均值）
--   044  对账·差异检测  -  订单金额与支付流水对账差异核查
--   045  日期差·物流异常  -  超时未签收订单与物流节点停滞监控
--   046  聚合·风险识别  -  多收货地址异常用户识别（刷单风险）
--   047  阈值·退货识别  -  高频退货用户与恶意退货嫌疑识别
--   048  对比·目标达成  -  门店月度销售目标达成率与缺口
--   049  订单内分析·连带率  -  订单商品连带率（每单平均商品数与品类数）
--   050  预测·移动平均  -  基于历史移动平均的日销售预测与残差
--   051  活跃度·DAU/MAU  -  日活/周活/月活与粘性指标（DAU/MAU）
--   052  转化·注册漏斗  -  注册用户到首单转化周期分析
--   053  分布·访问频次  -  用户访问频次分布与活跃分层
--   054  条件聚合·设备偏好  -  用户设备与渠道偏好交叉分析
--   055  窗口·浏览路径  -  会话内页面浏览路径（前 N 步序列）
--   056  排名·热门商品  -  商品曝光-点击-购买转化排行
--   057  窗口·停留时长  -  商品详情页停留时长估算（相邻事件间隔）
--   058  会话切分  -  基于 30 分钟不活跃切分用户会话并统计
--   059  新客行为  -  新用户首周行为完整性与引导效果
--   060  聚合·评价分析  -  商品评价分布与评分集中度
--   061  文本·评价质量  -  评价文本长度与评分相关性（低分长评识别）
--   062  复购·同商品  -  同商品重复购买用户与复购周期
--   063  路径·消费升级  -  用户价格带升级路径（低价到高价迁移）
--   064  价格带分析  -  品类价格带销售结构与主销价格区间
--   065  份额·品牌竞争  -  同品类品牌份额与集中度（HHI 指数）
--   066  毛利率·盈利分析  -  商品毛利率分析与低毛利商品预警
--   067  贡献·增长分解  -  品类增长贡献分解（各品类对总增长拉动）
--   068  预测·趋势外推  -  基于近 3 月趋势的商品销量外推预测
--   069  促销·毛利影响  -  促销商品毛利侵蚀与净收益评估
--   070  优惠券·叠加  -  多券叠加使用与订单优惠结构分析
--   071  大促·活动复盘  -  大促活动期间销售爆发与前后对比
--   072  渠道·获客质量  -  渠道新客获取质量（首单金额与留存）
--   073  投入产出·营销  -  营销活动投入产出比（ROI）综合评估
--   074  分层·营销名单  -  高价值流失风险用户精准营销名单
--   075  多仓·调拨建议  -  多仓库库存分布与调拨建议
--   076  周转·仓库健康  -  仓库库存周转天数与库存健康度评估
--   077  对比·承运商  -  承运商时效与异常率对比评估
--   078  逆向·退货物流  -  退货逆向物流时效与成本分析
--   079  区域·签收异常  -  区域签收异常率与高发省份识别
--   080  拆单分析  -  订单拆单率与拆单原因分析
--   081  支付·方式偏好  -  支付方式偏好、成功率与金额分布
--   082  异常·支付监控  -  支付失败率日趋势与突增告警
--   083  风控·大额审核  -  大额订单风险分级与人工审核名单
--   084  状态·订单健康  -  订单状态分布与异常状态占比趋势
--   085  库存·缺货预警  -  低库存商品占比与缺货风险品类分布
--   086  季节性·销售指数  -  月度季节性指数（季节因素对销售影响）
--   087  波动·峰值识别  -  销售峰值日识别与波动率分析
--   088  对比·周末效应  -  工作日与周末销售/流量差异对比
--   089  排名·城市消费力  -  城市消费力排名与人均消费分层
--   090  渗透·市场分析  -  区域市场渗透率与增长潜力评估
--   091  复购·门店维度  -  门店客户复购率与忠诚度对比
--   092  结构·门店品类  -  门店品类销售结构差异与特色品类识别
--   093  产出·门店效能  -  门店单店产出与销售集中度分析
--   094  预警·门店关停  -  门店经营衰退预警（双降门店识别）
--   095  迁移·价值变化  -  用户价值分层迁移（近 3 月 vs 前 3 月）
--   096  分层·价值识别  -  高频低价值用户识别与提升潜力评估
--   097  敏感度·价格弹性  -  用户折扣敏感度分层与精准定价建议
--   098  依赖·促销分析  -  品类促销依赖度与正价销售能力
--   099  效率·曝光转化  -  商品曝光到购买的转化效率与优化排序
--   100  转化·详情页  -  详情页浏览深度与加购转化关系
--   101  画像·标签聚合  -  用户多维度画像标签聚合
--   102  生命周期·阶段划分  -  用户生命周期阶段（引入/成长/成熟/衰退/流失）
--   103  渗透·品类扩展  -  品类交叉渗透率与扩展机会矩阵
--   104  新品·成功率  -  新品上市成功率与早期表现评估
--   105  长尾·贡献分析  -  长尾商品贡献度与集中度分析
--   106  预警·评分下滑  -  商品评分下滑趋势预警
--   107  分布·周转天数  -  库存周转天数分布与呆滞库存识别
--   108  资金·回款周期  -  订单到回款周期分析与资金占用
--   109  看板·经营指标  -  核心经营指标日看板（多指标横向对比）
--   110  健康度·增长质量  -  用户增长健康度（新增/活跃/留存/流失全景）
--
-- 【金融】100 条
--   111  分层·账户余额  -  账户余额分层与客户资产分布
--   112  窗口·余额变动  -  账户月度余额均值与环比变动
--   113  TOPN·大额交易  -  各分支行大额交易 TopN 与占比
--   114  异常·交易检测  -  账户交易金额异常检测（偏离历史均值）
--   115  频次·频繁交易  -  短时高频交易识别（时间窗口计数）
--   116  集中度·交易对手  -  客户交易对手集中度分析
--   117  反洗钱·资金回流  -  快进快出（资金短期回流）可疑模式识别
--   118  活跃·账户状态  -  账户休眠识别与激活转化分析
--   119  汇总·客户资产  -  客户资产全景（存款+理财+账户余额）
--   120  帕累托·AUM贡献  -  客户 AUM 排名与帕累托贡献分析
--   121  结构·存款分析  -  存款期限结构与到期分布
--   122  到期·存款流失  -  存款到期分布与续存流失预警
--   123  结构·存贷分析  -  分支行存贷比与资金运用效率
--   124  分层·客户价值  -  客户价值分层（按 AUM 与产品持有数）
--   125  交叉·产品持有  -  客户产品持有交叉分析与交叉销售机会
--   126  偏好·交易渠道  -  交易渠道偏好与渠道迁移趋势
--   127  异常·非营业时间  -  非营业时间交易监控
--   128  异地·交易监控  -  异地/非常用地交易识别
--   129  关联·账户网络  -  同客户多账户资金往来与关联交易
--   130  反洗钱·拆分交易  -  拆分交易（化整为零）规避监测识别
--   131  循环·资金链路  -  循环转账链路检测（A→B→C→A）
--   132  分布·风险事件  -  风险事件类型分布与等级统计
--   133  画像·风险客户  -  高风险客户画像与综合风险评分
--   134  分布·信用评分  -  信用评分分布与风险分层
--   135  趋势·评分变化  -  客户信用评分变化趋势与恶化预警
--   136  关联·评分违约  -  信用评分与贷款违约率关联分析
--   137  监控·高风险客户  -  高风险客户交易行为实时监控
--   138  合规·可疑报告  -  可疑交易报告（STR）候选名单生成
--   139  迁移·风险等级  -  客户风险等级迁移矩阵
--   140  分位数·阈值  -  交易金额分位数与监测阈值建议
--   141  转化·开户激活  -  账户开户到首笔交易转化分析
--   142  活跃·MAU分析  -  客户月度活跃度与留存（MAU/留存率）
--   143  LTV·客户价值  -  客户生命周期价值（金融 LTV）评估
--   144  流失·预警模型  -  客户流失预警（活跃度衰减 + 资产流出）
--   145  流失·资产流出  -  客户资产净流出监控与挽留优先级
--   146  排名·分支行规模  -  分支行存款规模排名与市场份额
--   147  质量·贷款资产  -  分支行贷款质量与不良率排名
--   148  盈利·分支行  -  分支行盈利贡献与成本收入比
--   149  趋势·财务指标  -  分支行财务指标同比与环比趋势
--   150  息差·资金成本  -  净息差（NIM）与资金成本分析
--   151  消费·银行卡  -  银行卡消费行为与额度使用分析
--   152  监控·境外交易  -  境外交易监控与异常识别
--   153  MCC·商户类别  -  商户类别（MCC）消费结构与风险分布
--   154  额度·用信分析  -  信用卡额度使用率与提额建议
--   155  逾期·信用卡  -  信用卡逾期分析与催收优先级
--   156  套现·嫌疑识别  -  信用卡套现嫌疑识别（大额整数 + 低频商户）
--   157  画像·交易行为  -  客户交易行为画像（金额/频次/时段/渠道）
--   158  链路·资金追踪  -  资金链路追踪（多层转账路径展开）
--   159  敞口·风险汇总  -  客户风险敞口汇总（贷款 + 信用卡 + 担保）
--   160  看板·合规指标  -  合规与风险监控日报（多指标汇总）
--   161  账龄·贷款分析  -  贷款账龄分析与逾期阶段分布
--   162  迁徙·逾期迁移  -  逾期阶段迁徙矩阵（滚动率分析）
--   163  回收·不良处置  -  不良贷款回收率与核销分析
--   164  拨备·充足率  -  贷款拨备计提与拨备充足率
--   165  收益·贷款定价  -  贷款组合收益率与定价分析
--   166  结构·期限分析  -  贷款期限结构与重定价缺口
--   167  多头·借贷识别  -  多头借贷客户识别与共债风险
--   168  审批·通过率  -  贷款申请通过率与客户画像关联
--   169  行为·还款分析  -  客户还款行为分析与违约先导指标
--   170  提前·还款分析  -  提前还款识别与利息损失测算
--   171  盈利·产品对比  -  贷款产品盈利能力对比（收益-风险-成本）
--   172  偿债·负债分析  -  客户负债率与偿债能力评估
--   173  集中·贷款分布  -  贷款集中度分析（产品/分支/客户）
--   174  RWA·风险资产  -  风险加权资产（RWA）测算与资本占用
--   175  ECL·预期损失  -  预期信用损失（ECL）三阶段测算
--   176  趋势·贷款发放  -  贷款发放趋势与季节性分析
--   177  业绩·信贷经理  -  信贷经理业绩排名与资产质量
--   178  催收·效果分析  -  逾期催收效果与回收率分析
--   179  展期·贷款重组  -  贷款展期与重组识别
--   180  看板·信贷质量  -  信贷组合质量综合看板
--   181  净值·基金收益  -  基金净值增长率与累计收益分析
--   182  回撤·风险控制  -  基金最大回撤与恢复期分析
--   183  夏普·风险调整  -  基金夏普比率与风险调整收益排名
--   184  排名·基金业绩  -  基金业绩排名与同类分位数
--   185  波动·基金风险  -  基金净值波动率与下行风险
--   186  持仓·客户收益  -  客户基金持仓收益与浮动盈亏
--   187  定投·收益模拟  -  基金定投成本与收益分析（按持有期）
--   188  申赎·资金流  -  基金申赎资金流与净流入分析
--   189  经理·业绩评价  -  基金经理管理规模与业绩评价
--   190  对比·基金类型  -  基金类型风险收益特征对比
--   191  集中·持仓分析  -  客户持仓集中度与分散度评估
--   192  回本·持仓分析  -  客户持仓回本分析与套牢识别
--   193  归因·收益分解  -  客户组合收益归因（按基金类型）
--   194  匹配·风险偏好  -  客户风险偏好与持仓风险匹配度
--   195  到期·理财收益  -  存款到期收益与利息支出测算
--   196  趋势·利润表  -  分支行利润表趋势与环比分析
--   197  结构·资产负债  -  资产负债结构与财务杠杆分析
--   198  预算·执行分析  -  预算执行率与偏差分析
--   199  同比·财务对比  -  财务指标同比（YoY）对比分析
--   200  杜邦·盈利分解  -  杜邦分析（利润率 × 资产周转 × 杠杆）
--   201  汇率·变动影响  -  汇率变动趋势与波动分析
--   202  敞口·外汇风险  -  外汇敞口估算与汇率敏感性
--   203  成本·中心分析  -  成本中心费用分析与效率评估
--   204  人效·人均产出  -  分支行人均产出与人员效率
--   205  结构·收入分析  -  收入结构分析与中收占比
--   206  质量·增长分析  -  收入增长质量与可持续性评估
--   207  综合·经营分析  -  分支行综合经营分析（规模+质量+效益）
--   208  贡献·客户综合  -  客户综合贡献度与价值评级
--   209  盈利·产品分析  -  产品线盈利分析与资源优化建议
--   210  看板·全景指标  -  金融机构全景经营看板（规模/质量/效益）
--
-- 【人力】75 条
--   211  递归·组织架构  -  组织架构树递归展开与层级路径
--   212  统计·部门规模  -  部门人数、层级与下属部门统计
--   213  递归·汇报链  -  员工汇报链路与到 CEO 层级深度
--   214  扁平度·管理幅度  -  管理者管理幅度（Span of Control）分析
--   215  层级·组织深度  -  组织层级深度与扁平化程度评估
--   216  分布·司龄分析  -  员工司龄分布与留存分析
--   217  结构·年龄分析  -  员工年龄结构与代际分布
--   218  交叉·职级性别  -  职级与性别交叉分布分析
--   219  趋势·人员流动  -  月度入职离职趋势与净增长
--   220  流失·离职分析  -  员工流失率与离职高峰分析
--   221  留存·新员工  -  新员工留存率（入职后 3/6/12 个月）
--   222  风险·离职预警  -  部门离职风险与关键岗位流失预警
--   223  排名·流动率  -  部门人员流动率排名与对比
--   224  异动·调岗分析  -  员工异动（调岗/晋升）记录分析
--   225  人才·关键识别  -  关键人才识别与保留优先级
--   226  成本·人力总额  -  人力成本总额与月度趋势
--   227  占比·部门成本  -  部门人力成本占比与人均成本
--   228  带宽·薪酬分析  -  职级薪酬带宽与分位数分析
--   229  公平·同工同酬  -  同职级薪酬差异与同工同酬分析
--   230  竞争·薪酬定位  -  薪酬竞争力分析（内部分位 vs 市场中位）
--   231  调薪·幅度分析  -  调薪幅度分布与调薪覆盖率
--   232  关联·调薪绩效  -  调薪幅度与绩效得分关联分析
--   233  预算·薪酬执行  -  薪酬预算执行率与偏差分析
--   234  排名·薪酬分位  -  员工薪酬排名与部门内分位
--   235  加班·费用分析  -  加班时长与加班费分析
--   236  税费·社保分析  -  个税与社保负担分析
--   237  分布·实发工资  -  实发工资分布与收入离散度
--   238  人均·部门薪酬  -  部门人均薪酬与薪酬效率
--   239  结构·固浮比  -  薪酬结构分析（基本工资/奖金/津贴占比）
--   240  出勤·考勤分析  -  员工出勤率与缺勤分析
--   241  迟到·异常识别  -  迟到早退分析与高频异常员工
--   242  加班·部门对比  -  部门加班强度与健康度预警
--   243  连续·异常考勤  -  连续异常考勤识别（连续迟到/缺勤）
--   244  假期·使用分析  -  假期使用情况与类型分布
--   245  假期·余额预警  -  假期额度使用与过期预警
--   246  分布·请假类型  -  请假类型与部门/月份交叉分布
--   247  时长·工作分布  -  员工工作时长分布与效率分析
--   248  弹性·远程办公  -  弹性办公与在岗模式分析
--   249  关联·考勤绩效  -  考勤表现与绩效得分关联分析
--   250  看板·考勤汇总  -  月度考勤综合看板
--   251  分位·薪酬带宽  -  薪酬分位数（P25/P50/P75/P90）与带宽设计
--   252  轨迹·薪酬增长  -  员工薪酬增长轨迹与调薪节奏
--   253  集中·高薪分析  -  高薪员工集中度与薪酬差距
--   254  效能·人力产出  -  人力成本产出比与效能分析
--   255  编制·执行率  -  编制执行率与招聘缺口分析
--   256  效能·人均产出  -  组织人均产出与效能对比
--   257  画像·员工综合  -  员工综合画像（绩效+薪酬+司龄+考勤）
--   258  协作·跨部门  -  跨部门项目协作网络分析
--   259  团队·管理者分析  -  管理者团队构成与团队健康度
--   260  看板·人力全景  -  人力资源全景看板（规模/成本/效能）
--   261  漏斗·招聘转化  -  招聘漏斗各环节转化率与瓶颈识别
--   262  周期·招聘时效  -  招聘需求关闭周期与积压情况分析
--   263  渠道·招聘来源  -  招聘渠道效果与成本效益对比
--   264  薪酬·Offer竞争  -  Offer 薪资竞争力与接受率关联分析
--   265  留存·试用期  -  新员工试用期通过率与入职来源质量
--   266  培训·覆盖完成  -  培训覆盖率、完成率与学时结构分析
--   267  关联·培训绩效  -  培训投入与绩效提升的关联分析
--   268  投入·培训产出  -  培训成本投入与人均产出效益评估
--   269  项目·人力投入  -  项目人力投入结构与成本分摊分析
--   270  负荷·并行冲突  -  员工并行项目负荷与资源冲突识别
--   271  进度·项目配置  -  项目周期、人力配置与交付效率对比
--   272  梯队·继任计划  -  关键岗位继任梯队与就绪度评估
--   273  流动·内部轮岗  -  内部岗位流动、轮岗与晋升活跃度分析
--   274  晋升·速度分析  -  晋升速度、职级跃迁与停滞识别
--   275  流失·原因归因  -  离职原因分布与可归因风险因素交叉分析
--   276  多元·包容分析  -  性别与年龄结构的多元化分布及均衡度
--   277  敬业·代理指标  -  员工敬业度代理指标与团队氛围评估
--   278  预算·人力执行  -  人力成本预算与实际发放的执行对比
--   279  趋势·成本同比  -  人力成本年度同比与结构变化分解
--   280  效率·加班成本  -  加班投入与产出效率的成本效益分析
--   281  组织·层级效能  -  组织层级深度与管理成本效能分析
--   282  价值·员工回报  -  员工生命周期价值与人力投入回报评估
--   283  风险·岗位空缺  -  关键岗位空缺风险与业务连续性评估
--   284  预警·人力风险  -  人力综合风险预警（流失+负荷+成本+绩效）
--   285  看板·部门健康  -  部门人力健康度综合看板
--
-- 【数据治理】4 条
--   286  质量·缺失检测  -  核心业务表关键字段缺失与异常检测
--   287  质量·重复识别  -  重复记录识别与主数据合并建议
--   288  质量·一致性  -  跨表主数据一致性与引用完整性检查
--   289  对账·订单支付  -  订单金额与支付流水差异对账
--
-- 【综合】11 条
--   290  跨域·客户价值  -  电商消费与金融资产的客户综合价值分层
--   291  跨域·门店效能  -  门店销售指标与人力配置效能联动分析
--   292  跨域·分支行对比  -  分支行存贷规模、客户数与盈利综合排名
--   293  下钻·多维分析  -  城市×品类×月份的销售多维下钻汇总
--   294  波动·异常检测  -  核心指标时间序列的统计异常检测
--   295  对比·同比环比  -  关键经营指标同比环比与复合增长率
--   296  滚动·移动窗口  -  滚动12月移动窗口指标与趋势平滑
--   297  帕累托·贡献度  -  全业务帕累托分析与关键少数识别
--   298  达成·目标看板  -  目标达成率看板与差距归因分析
--   299  看板·KPI全景  -  全业务域核心 KPI 全景看板
--   300  汇总·决策支持  -  面向管理层的跨业务域决策支持汇总
--
-- ==============================================================================

-- ------------------------------------------------------------------------------
-- [001] 窗口函数·分组排名 | 电商 | 各城市销售额 Top3 商品及城市内占比
-- ------------------------------------------------------------------------------
WITH prod_city AS (
    SELECT c.city,
           p.product_id,
           p.product_name,
           SUM(oi.amount)   AS sales_amt,
           SUM(oi.quantity) AS sales_qty
    FROM orders o
    JOIN customers   c  ON o.customer_id = c.customer_id
    JOIN order_items oi ON o.order_id = oi.order_id
    JOIN products    p  ON oi.product_id = p.product_id
    WHERE o.status = 'completed'
      AND o.order_date >= (TRUNC(SYSDATE) - 180)
    GROUP BY c.city, p.product_id, p.product_name
),
ranked AS (
    SELECT city, product_id, product_name, sales_amt, sales_qty,
           ROW_NUMBER() OVER (PARTITION BY city ORDER BY sales_amt DESC) AS rn,
           SUM(sales_amt) OVER (PARTITION BY city)                       AS city_total
    FROM prod_city
)
SELECT city, product_name, sales_amt, sales_qty, rn AS city_rank,
       ROUND(sales_amt / NULLIF(city_total, 0) * 100, 2) AS pct_in_city
FROM ranked
WHERE rn <= 3
ORDER BY city, rn
FETCH FIRST 300 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [002] 窗口函数·环比计算 | 电商 | 月度 GMV 及环比增长率
-- ------------------------------------------------------------------------------
WITH m AS (
    SELECT TO_CHAR(order_date, 'YYYY-MM') AS ym,
           SUM(pay_amount) AS gmv,
           COUNT(DISTINCT order_id) AS order_cnt
    FROM orders
    WHERE status = 'completed'
    GROUP BY TO_CHAR(order_date, 'YYYY-MM')
)
SELECT ym, gmv, order_cnt,
       LAG(gmv) OVER (ORDER BY ym) AS prev_gmv,
       ROUND((gmv - LAG(gmv) OVER (ORDER BY ym))
             / NULLIF(LAG(gmv) OVER (ORDER BY ym), 0) * 100, 2) AS mom_pct,
       SUM(gmv) OVER (ORDER BY ym ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS cum_gmv
FROM m
ORDER BY ym;

-- ------------------------------------------------------------------------------
-- [003] 窗口函数·累计求和 | 电商 | 门店累计销售额与年度目标达成率
-- ------------------------------------------------------------------------------
WITH store_sales AS (
    SELECT s.store_id, s.store_name, s.region,
           SUM(o.pay_amount) AS gmv
    FROM orders o
    JOIN stores s ON o.store_id = s.store_id
    WHERE o.status = 'completed'
      AND EXTRACT(YEAR FROM o.order_date) = EXTRACT(YEAR FROM TRUNC(SYSDATE))
    GROUP BY s.store_id, s.store_name, s.region
)
SELECT store_name, region, gmv,
       SUM(gmv) OVER (PARTITION BY region ORDER BY gmv DESC
                      ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS region_cum_gmv,
       ROUND(gmv / NULLIF(SUM(gmv) OVER (PARTITION BY region), 0) * 100, 2) AS region_share_pct,
       RANK() OVER (ORDER BY gmv DESC) AS all_store_rank
FROM store_sales
ORDER BY gmv DESC;

-- ------------------------------------------------------------------------------
-- [004] 窗口函数·移动平均 | 电商 | 近 7 日移动平均 GMV 与趋势判断
-- ------------------------------------------------------------------------------
WITH d AS (
    SELECT CAST(order_date AS DATE) AS dt,
           SUM(pay_amount) AS gmv
    FROM orders
    WHERE status = 'completed'
      AND order_date >= (TRUNC(SYSDATE) - 90)
    GROUP BY CAST(order_date AS DATE)
)
SELECT dt, gmv,
       ROUND(AVG(gmv) OVER (ORDER BY dt ROWS BETWEEN 6 PRECEDING AND CURRENT ROW), 2) AS ma7,
       ROUND(AVG(gmv) OVER (ORDER BY dt ROWS BETWEEN 29 PRECEDING AND CURRENT ROW), 2) AS ma30,
       ROUND(gmv - AVG(gmv) OVER (ORDER BY dt ROWS BETWEEN 6 PRECEDING AND CURRENT ROW), 2) AS diff_ma7,
       CASE WHEN gmv > AVG(gmv) OVER (ORDER BY dt ROWS BETWEEN 6 PRECEDING AND CURRENT ROW) * 1.3
            THEN 'spike'
            WHEN gmv < AVG(gmv) OVER (ORDER BY dt ROWS BETWEEN 6 PRECEDING AND CURRENT ROW) * 0.7
            THEN 'slump'
            ELSE 'normal' END AS trend_flag
FROM d
ORDER BY dt;

-- ------------------------------------------------------------------------------
-- [005] 窗口函数·占比分析 | 电商 | 各渠道 GMV 及其在总盘中占比
-- ------------------------------------------------------------------------------
WITH ch AS (
    SELECT channel,
           COUNT(DISTINCT customer_id) AS uv,
           COUNT(order_id)             AS order_cnt,
           SUM(pay_amount)             AS gmv,
           AVG(pay_amount)             AS avg_order_amt
    FROM orders
    WHERE status = 'completed'
      AND order_date >= (TRUNC(SYSDATE) - 30)
    GROUP BY channel
)
SELECT channel, uv, order_cnt, gmv, ROUND(avg_order_amt, 2) AS avg_order_amt,
       ROUND(gmv / NULLIF(SUM(gmv) OVER (), 0) * 100, 2)          AS gmv_share_pct,
       ROUND(order_cnt / NULLIF(SUM(order_cnt) OVER (), 0) * 100, 2) AS order_share_pct,
       ROUND(gmv / NULLIF(uv, 0), 2)                              AS gmv_per_user
FROM ch
ORDER BY gmv DESC;

-- ------------------------------------------------------------------------------
-- [006] 窗口函数·帕累托分析 | 电商 | 商品 ABC 分类（累计销售占比 80/95/100）
-- ------------------------------------------------------------------------------
WITH prod AS (
    SELECT p.product_id, p.product_name, p.category_id,
           SUM(oi.amount) AS sales_amt
    FROM order_items oi
    JOIN products p ON oi.product_id = p.product_id
    JOIN orders   o ON oi.order_id = o.order_id
    WHERE o.status = 'completed'
    GROUP BY p.product_id, p.product_name, p.category_id
),
cum AS (
    SELECT product_name, sales_amt,
           SUM(sales_amt) OVER (ORDER BY sales_amt DESC
                ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS cum_amt,
           SUM(sales_amt) OVER () AS total_amt
    FROM prod
)
SELECT product_name, sales_amt,
       ROUND(cum_amt / NULLIF(total_amt, 0) * 100, 2) AS cum_pct,
       CASE WHEN cum_amt / NULLIF(total_amt, 0) <= 0.80 THEN 'A'
            WHEN cum_amt / NULLIF(total_amt, 0) <= 0.95 THEN 'B'
            ELSE 'C' END AS abc_class
FROM cum
ORDER BY sales_amt DESC;

-- ------------------------------------------------------------------------------
-- [007] 自连接·同比分析 | 电商 | 品类销售额同比（YoY）分析
-- ------------------------------------------------------------------------------
WITH cy AS (
    SELECT p.category_id, c.category_name,
           SUM(oi.amount) AS amt_cur
    FROM order_items oi
    JOIN products   p ON oi.product_id = p.product_id
    JOIN categories c ON p.category_id = c.category_id
    JOIN orders     o ON oi.order_id = o.order_id
    WHERE o.status = 'completed'
      AND EXTRACT(YEAR FROM o.order_date) = EXTRACT(YEAR FROM TRUNC(SYSDATE))
    GROUP BY p.category_id, c.category_name
),
py AS (
    SELECT p.category_id,
           SUM(oi.amount) AS amt_prev
    FROM order_items oi
    JOIN products p ON oi.product_id = p.product_id
    JOIN orders   o ON oi.order_id = o.order_id
    WHERE o.status = 'completed'
      AND EXTRACT(YEAR FROM o.order_date) = EXTRACT(YEAR FROM TRUNC(SYSDATE)) - 1
    GROUP BY p.category_id
)
SELECT cy.category_name, cy.amt_cur, COALESCE(py.amt_prev, 0) AS amt_prev,
       ROUND((cy.amt_cur - COALESCE(py.amt_prev, 0))
             / NULLIF(COALESCE(py.amt_prev, 0), 0) * 100, 2) AS yoy_pct
FROM cy
LEFT JOIN py ON cy.category_id = py.category_id
ORDER BY cy.amt_cur DESC;

-- ------------------------------------------------------------------------------
-- [008] 条件聚合·交叉矩阵 | 电商 | 品类 × 渠道 销售交叉矩阵
-- ------------------------------------------------------------------------------
SELECT c.category_name,
       SUM(CASE WHEN o.channel = 'app'      THEN oi.amount ELSE 0 END) AS app_amt,
       SUM(CASE WHEN o.channel = 'web'      THEN oi.amount ELSE 0 END) AS web_amt,
       SUM(CASE WHEN o.channel = 'miniapp'  THEN oi.amount ELSE 0 END) AS miniapp_amt,
       SUM(CASE WHEN o.channel = 'offline'  THEN oi.amount ELSE 0 END) AS offline_amt,
       SUM(oi.amount) AS total_amt,
       ROUND(SUM(CASE WHEN o.channel = 'app' THEN oi.amount ELSE 0 END)
             / NULLIF(SUM(oi.amount), 0) * 100, 2) AS app_share_pct
FROM order_items oi
JOIN orders     o ON oi.order_id = o.order_id
JOIN products   p ON oi.product_id = p.product_id
JOIN categories c ON p.category_id = c.category_id
WHERE o.status = 'completed'
  AND o.order_date >= (TRUNC(SYSDATE) - 90)
GROUP BY c.category_name
ORDER BY total_amt DESC;

-- ------------------------------------------------------------------------------
-- [009] CTE·新老客识别 | 电商 | 新老客户销售贡献对比
-- ------------------------------------------------------------------------------
WITH first_buy AS (
    SELECT customer_id, MIN(order_date) AS first_order_date
    FROM orders
    WHERE status = 'completed'
    GROUP BY customer_id
),
tagged AS (
    SELECT o.order_id, o.customer_id, o.pay_amount, o.order_date,
           CASE WHEN o.order_date = f.first_order_date THEN 'new' ELSE 'old' END AS cust_type
    FROM orders o
    JOIN first_buy f ON o.customer_id = f.customer_id
    WHERE o.status = 'completed'
      AND o.order_date >= (TRUNC(SYSDATE) - 60)
)
SELECT cust_type,
       COUNT(*)                    AS order_cnt,
       COUNT(DISTINCT customer_id) AS cust_cnt,
       SUM(pay_amount)             AS gmv,
       ROUND(AVG(pay_amount), 2)   AS avg_order_amt,
       ROUND(SUM(pay_amount) / NULLIF(SUM(SUM(pay_amount)) OVER (), 0) * 100, 2) AS gmv_share_pct
FROM tagged
GROUP BY cust_type;

-- ------------------------------------------------------------------------------
-- [010] 窗口函数·留存计算 | 电商 | 月度复购率与人均购买次数
-- ------------------------------------------------------------------------------
WITH mo AS (
    SELECT TO_CHAR(order_date, 'YYYY-MM') AS ym,
           customer_id,
           COUNT(*) AS buy_cnt
    FROM orders
    WHERE status = 'completed'
    GROUP BY TO_CHAR(order_date, 'YYYY-MM'), customer_id
)
SELECT ym,
       COUNT(*)                                                       AS active_cust,
       SUM(CASE WHEN buy_cnt > 1 THEN 1 ELSE 0 END)                   AS repeat_cust,
       ROUND(SUM(CASE WHEN buy_cnt > 1 THEN 1 ELSE 0 END)
             / NULLIF(COUNT(*), 0) * 100, 2)                          AS repeat_rate_pct,
       ROUND(AVG(buy_cnt), 2)                                         AS avg_buy_per_cust,
       MAX(buy_cnt)                                                   AS max_buy_cnt
FROM mo
GROUP BY ym
ORDER BY ym;

-- ------------------------------------------------------------------------------
-- [011] 分位数·NTILE | 电商 | 订单金额分层（五等分）与层均价值
-- ------------------------------------------------------------------------------
WITH bucketed AS (
    SELECT order_id, customer_id, pay_amount,
           NTILE(5) OVER (ORDER BY pay_amount DESC) AS amt_tier
    FROM orders
    WHERE status = 'completed'
      AND order_date >= (TRUNC(SYSDATE) - 90)
)
SELECT amt_tier,
       COUNT(*)                  AS order_cnt,
       MIN(pay_amount)           AS min_amt,
       MAX(pay_amount)           AS max_amt,
       ROUND(AVG(pay_amount), 2) AS avg_amt,
       SUM(pay_amount)           AS tier_gmv,
       ROUND(SUM(pay_amount) / NULLIF(SUM(SUM(pay_amount)) OVER (), 0) * 100, 2) AS gmv_share_pct
FROM bucketed
GROUP BY amt_tier
ORDER BY amt_tier;

-- ------------------------------------------------------------------------------
-- [012] 排名·门店人效 | 电商 | 门店销售排名与区域内份额
-- ------------------------------------------------------------------------------
WITH ss AS (
    SELECT s.store_id, s.store_name, s.region, s.city,
           COUNT(DISTINCT o.order_id) AS order_cnt,
           SUM(o.pay_amount)          AS gmv
    FROM stores s
    LEFT JOIN orders o ON s.store_id = o.store_id AND o.status = 'completed'
    GROUP BY s.store_id, s.store_name, s.region, s.city
)
SELECT store_name, city, region, order_cnt, gmv,
       RANK()       OVER (ORDER BY gmv DESC)                AS rank_all,
       DENSE_RANK() OVER (PARTITION BY region ORDER BY gmv DESC) AS rank_in_region,
       ROUND(gmv / NULLIF(SUM(gmv) OVER (PARTITION BY region), 0) * 100, 2) AS region_share_pct,
       ROUND(gmv / NULLIF(order_cnt, 0), 2) AS avg_ticket
FROM ss
ORDER BY gmv DESC;

-- ------------------------------------------------------------------------------
-- [013] 窗口函数·排名变化 | 电商 | 商品销售排名环比变化（上升/下降）
-- ------------------------------------------------------------------------------
WITH pm AS (
    SELECT oi.product_id, TO_CHAR(o.order_date, 'YYYY-MM') AS ym,
           SUM(oi.amount) AS amt
    FROM order_items oi
    JOIN orders o ON oi.order_id = o.order_id
    WHERE o.status = 'completed'
      AND o.order_date >= (TRUNC(SYSDATE) - 120)
    GROUP BY oi.product_id, TO_CHAR(o.order_date, 'YYYY-MM')
),
rk AS (
    SELECT product_id, ym, amt,
           ROW_NUMBER() OVER (PARTITION BY ym ORDER BY amt DESC) AS rk
    FROM pm
)
SELECT cur.product_id, p.product_name,
       cur.rk AS rank_cur, prev.rk AS rank_prev,
       COALESCE(prev.rk, 0) - cur.rk AS rank_change,
       cur.amt AS amt_cur, COALESCE(prev.amt, 0) AS amt_prev,
       CASE WHEN prev.rk IS NULL THEN 'new_in'
            WHEN cur.rk < prev.rk THEN 'up'
            WHEN cur.rk > prev.rk THEN 'down'
            ELSE 'flat' END AS move_flag
FROM rk cur
LEFT JOIN rk prev ON cur.product_id = prev.product_id AND prev.ym = TO_CHAR((TRUNC(TRUNC(SYSDATE), 'MM') - INTERVAL '1' MONTH), 'YYYY-MM')
JOIN products p ON cur.product_id = p.product_id
WHERE cur.ym = TO_CHAR(TRUNC(TRUNC(SYSDATE), 'MM'), 'YYYY-MM')
ORDER BY rank_change DESC;

-- ------------------------------------------------------------------------------
-- [014] 多表聚合·退款分析 | 电商 | 各品类退款率与退款原因分布
-- ------------------------------------------------------------------------------
WITH base AS (
    SELECT p.category_id, c.category_name,
           SUM(oi.amount) AS sales_amt
    FROM order_items oi
    JOIN products   p ON oi.product_id = p.product_id
    JOIN categories c ON p.category_id = c.category_id
    JOIN orders     o ON oi.order_id = o.order_id
    WHERE o.status = 'completed'
      AND o.order_date >= (TRUNC(SYSDATE) - 180)
    GROUP BY p.category_id, c.category_name
),
rf AS (
    SELECT p.category_id,
           SUM(r.refund_amount) AS refund_amt,
           COUNT(*)             AS refund_cnt,
           SUM(CASE WHEN r.reason = 'quality'  THEN 1 ELSE 0 END) AS cnt_quality,
           SUM(CASE WHEN r.reason = 'logistic' THEN 1 ELSE 0 END) AS cnt_logistic,
           SUM(CASE WHEN r.reason = 'wrong'    THEN 1 ELSE 0 END) AS cnt_wrong,
           SUM(CASE WHEN r.reason NOT IN ('quality', 'logistic', 'wrong') THEN 1 ELSE 0 END) AS cnt_other
    FROM refunds r
    JOIN orders   o ON r.order_id = o.order_id
    JOIN order_items oi ON o.order_id = oi.order_id
    JOIN products p ON oi.product_id = p.product_id
    WHERE r.refund_date >= (TRUNC(SYSDATE) - 180)
    GROUP BY p.category_id
)
SELECT b.category_name, b.sales_amt,
       COALESCE(rf.refund_amt, 0) AS refund_amt,
       ROUND(COALESCE(rf.refund_amt, 0) / NULLIF(b.sales_amt, 0) * 100, 2) AS refund_rate_pct,
       COALESCE(rf.cnt_quality, 0)  AS cnt_quality,
       COALESCE(rf.cnt_logistic, 0) AS cnt_logistic,
       COALESCE(rf.cnt_wrong, 0)    AS cnt_wrong,
       COALESCE(rf.cnt_other, 0)    AS cnt_other
FROM base b
LEFT JOIN rf ON b.category_id = rf.category_id
ORDER BY refund_rate_pct DESC;

-- ------------------------------------------------------------------------------
-- [015] 多表关联·营销效果 | 电商 | 优惠券核销率与营销 ROI
-- ------------------------------------------------------------------------------
WITH cp AS (
    SELECT cp.promo_id, pr.promo_name, pr.budget,
           COUNT(*)                                             AS issued_cnt,
           SUM(CASE WHEN cp.status = 'used' THEN 1 ELSE 0 END)   AS used_cnt
    FROM coupons cp
    JOIN promotions pr ON cp.promo_id = pr.promo_id
    GROUP BY cp.promo_id, pr.promo_name, pr.budget
),
used_amt AS (
    SELECT cp.promo_id,
           SUM(o.pay_amount)  AS gmv,
           SUM(cp.face_value) AS discount_amt
    FROM coupons cp
    JOIN orders o ON cp.order_id = o.order_id
    WHERE cp.status = 'used'
    GROUP BY cp.promo_id
)
SELECT cp.promo_name, cp.issued_cnt, cp.used_cnt,
       ROUND(cp.used_cnt / NULLIF(cp.issued_cnt, 0) * 100, 2) AS use_rate_pct,
       COALESCE(ua.gmv, 0)          AS gmv,
       COALESCE(ua.discount_amt, 0) AS discount_amt,
       ROUND(COALESCE(ua.gmv, 0) / NULLIF(COALESCE(ua.discount_amt, 0) + cp.budget, 0), 2) AS roi
FROM cp
LEFT JOIN used_amt ua ON cp.promo_id = ua.promo_id
ORDER BY roi DESC;

-- ------------------------------------------------------------------------------
-- [016] 条件聚合·客单价分层 | 电商 | 客单价分布与城市消费力分层
-- ------------------------------------------------------------------------------
WITH cust_amt AS (
    SELECT c.customer_id, c.city,
           SUM(o.pay_amount) AS total_amt,
           COUNT(*)          AS order_cnt,
           AVG(o.pay_amount) AS avg_ticket
    FROM customers c
    JOIN orders o ON c.customer_id = o.customer_id
    WHERE o.status = 'completed'
      AND o.order_date >= (TRUNC(SYSDATE) - 365)
    GROUP BY c.customer_id, c.city
)
SELECT city,
       COUNT(*) AS cust_cnt,
       SUM(CASE WHEN avg_ticket < 100  THEN 1 ELSE 0 END) AS tier_low,
       SUM(CASE WHEN avg_ticket >= 100 AND avg_ticket < 300 THEN 1 ELSE 0 END) AS tier_mid,
       SUM(CASE WHEN avg_ticket >= 300 AND avg_ticket < 800 THEN 1 ELSE 0 END) AS tier_high,
       SUM(CASE WHEN avg_ticket >= 800 THEN 1 ELSE 0 END) AS tier_vip,
       ROUND(AVG(avg_ticket), 2) AS city_avg_ticket,
       ROUND(SUM(total_amt) / NULLIF(SUM(order_cnt), 0), 2) AS city_real_ticket
FROM cust_amt
GROUP BY city
HAVING COUNT(*) >= 10
ORDER BY city_avg_ticket DESC;

-- ------------------------------------------------------------------------------
-- [017] 时间维度·小时分析 | 电商 | 分时段销售热力（按小时 + 工作日/周末）
-- ------------------------------------------------------------------------------
SELECT EXTRACT(HOUR FROM CAST(o.order_date AS TIMESTAMP)) AS hour_of_day,
       CASE WHEN (TO_NUMBER(TO_CHAR(o.order_date, 'D')) - 1) IN (0, 6) THEN 'weekend' ELSE 'weekday' END AS day_type,
       COUNT(*)                  AS order_cnt,
       SUM(o.pay_amount)         AS gmv,
       ROUND(AVG(o.pay_amount), 2) AS avg_amt,
       COUNT(DISTINCT o.customer_id) AS uv
FROM orders o
WHERE o.status = 'completed'
  AND o.order_date >= (TRUNC(SYSDATE) - 30)
GROUP BY EXTRACT(HOUR FROM CAST(o.order_date AS TIMESTAMP)),
         CASE WHEN (TO_NUMBER(TO_CHAR(o.order_date, 'D')) - 1) IN (0, 6) THEN 'weekend' ELSE 'weekday' END
ORDER BY hour_of_day, day_type;

-- ------------------------------------------------------------------------------
-- [018] 自连接·购物篮分析 | 电商 | 跨品类连带购买（品类对共现）分析
-- ------------------------------------------------------------------------------
WITH order_cat AS (
    SELECT oi.order_id, p.category_id
    FROM order_items oi
    JOIN products p ON oi.product_id = p.product_id
    JOIN orders   o ON oi.order_id = o.order_id
    WHERE o.status = 'completed'
    GROUP BY oi.order_id, p.category_id
),
pairs AS (
    SELECT a.category_id AS cat_a, b.category_id AS cat_b, COUNT(*) AS pair_cnt
    FROM order_cat a
    JOIN order_cat b ON a.order_id = b.order_id AND a.category_id < b.category_id
    GROUP BY a.category_id, b.category_id
),
cat_total AS (
    SELECT category_id, COUNT(*) AS cat_order_cnt FROM order_cat GROUP BY category_id
)
SELECT ca.category_name AS category_a,
       cb.category_name AS category_b,
       pr.pair_cnt,
       ROUND(pr.pair_cnt / NULLIF(ta.cat_order_cnt, 0) * 100, 2) AS lift_a_pct,
       ROUND(pr.pair_cnt / NULLIF(tb.cat_order_cnt, 0) * 100, 2) AS lift_b_pct
FROM pairs pr
JOIN categories ca ON pr.cat_a = ca.category_id
JOIN categories cb ON pr.cat_b = cb.category_id
JOIN cat_total ta  ON pr.cat_a = ta.category_id
JOIN cat_total tb  ON pr.cat_b = tb.category_id
WHERE pr.pair_cnt >= 20
ORDER BY pr.pair_cnt DESC
FETCH FIRST 100 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [019] 条件聚合·促销对比 | 电商 | 促销期与非促销期销售对比
-- ------------------------------------------------------------------------------
WITH tagged AS (
    SELECT o.order_id, o.pay_amount, o.order_date,
           CASE WHEN EXISTS (
                    SELECT 1 FROM promotions pm
                    WHERE o.order_date BETWEEN pm.start_date AND pm.end_date
                      AND (pm.category_id IS NULL OR pm.category_id = p.category_id)
                ) THEN 'promo' ELSE 'normal' END AS period_type,
           p.category_id
    FROM orders o
    JOIN order_items oi ON o.order_id = oi.order_id
    JOIN products    p  ON oi.product_id = p.product_id
    WHERE o.status = 'completed'
)
SELECT category_id,
       COUNT(CASE WHEN period_type = 'promo'  THEN 1 END) AS promo_orders,
       COUNT(CASE WHEN period_type = 'normal' THEN 1 END) AS normal_orders,
       ROUND(AVG(CASE WHEN period_type = 'promo'  THEN pay_amount END), 2) AS promo_avg_amt,
       ROUND(AVG(CASE WHEN period_type = 'normal' THEN pay_amount END), 2) AS normal_avg_amt,
       ROUND(AVG(CASE WHEN period_type = 'promo'  THEN pay_amount END)
             / NULLIF(AVG(CASE WHEN period_type = 'normal' THEN pay_amount END), 0), 2) AS promo_lift
FROM tagged
GROUP BY category_id
ORDER BY promo_lift DESC;

-- ------------------------------------------------------------------------------
-- [020] 分桶·履约时效 | 电商 | 订单履约时效分布（下单到签收）
-- ------------------------------------------------------------------------------
WITH dur AS (
    SELECT o.order_id, s.province, s.carrier,
           (s.ship_date - o.order_date) AS deliver_days
    FROM orders o
    JOIN shipments s ON o.order_id = s.order_id
    WHERE s.status = 'signed'
      AND o.order_date >= (TRUNC(SYSDATE) - 90)
)
SELECT province,
       COUNT(*)                                                          AS order_cnt,
       ROUND(AVG(deliver_days), 2)                                       AS avg_days,
       MAX(deliver_days)                                                 AS max_days,
       SUM(CASE WHEN deliver_days <= 1 THEN 1 ELSE 0 END)                AS d1,
       SUM(CASE WHEN deliver_days BETWEEN 2 AND 3 THEN 1 ELSE 0 END)     AS d2_3,
       SUM(CASE WHEN deliver_days BETWEEN 4 AND 7 THEN 1 ELSE 0 END)     AS d4_7,
       SUM(CASE WHEN deliver_days > 7 THEN 1 ELSE 0 END)                 AS d_over7,
       ROUND(SUM(CASE WHEN deliver_days <= 3 THEN 1 ELSE 0 END)
             / NULLIF(COUNT(*), 0) * 100, 2)                             AS within3_pct
FROM dur
GROUP BY province
ORDER BY within3_pct ASC;

-- ------------------------------------------------------------------------------
-- [021] 间隙与孤岛·连续行为 | 电商 | 连续下单用户识别（连续 N 天有订单）
-- ------------------------------------------------------------------------------
WITH uc AS (
    SELECT customer_id, CAST(order_date AS DATE) AS dt
    FROM orders
    WHERE status = 'completed'
    GROUP BY customer_id, CAST(order_date AS DATE)
),
grp AS (
    SELECT customer_id, dt,
           (dt - DATE '2000-01-01')
             - ROW_NUMBER() OVER (PARTITION BY customer_id ORDER BY dt) AS island_id
    FROM uc
),
runs AS (
    SELECT customer_id, island_id,
           COUNT(*)           AS streak_days,
           MIN(dt)            AS start_dt,
           MAX(dt)            AS end_dt
    FROM grp
    GROUP BY customer_id, island_id
)
SELECT customer_id, streak_days, start_dt, end_dt
FROM runs
WHERE streak_days >= 3
ORDER BY streak_days DESC, customer_id
FETCH FIRST 200 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [022] 递归CTE·日期补全 | 电商 | 生成完整日期序列并补全缺失销售日
-- ------------------------------------------------------------------------------
WITH date_seq(dt) AS (
    SELECT DATE '2024-01-01' AS dt FROM dual
    UNION ALL
    SELECT CAST((dt + 1) AS DATE) FROM date_seq WHERE dt < DATE '2024-03-31'
),
daily AS (
    SELECT CAST(order_date AS DATE) AS dt, SUM(pay_amount) AS gmv
    FROM orders
    WHERE status = 'completed'
      AND order_date >= DATE '2024-01-01'
      AND order_date <  DATE '2024-04-01'
    GROUP BY CAST(order_date AS DATE)
)
SELECT ds.dt,
       COALESCE(dl.gmv, 0) AS gmv,
       CASE WHEN dl.gmv IS NULL THEN 'missing' ELSE 'ok' END AS data_flag,
       ROUND(AVG(COALESCE(dl.gmv, 0)) OVER (ORDER BY ds.dt ROWS BETWEEN 6 PRECEDING AND CURRENT ROW), 2) AS ma7
FROM date_seq ds
LEFT JOIN daily dl ON ds.dt = dl.dt
ORDER BY ds.dt;

-- ------------------------------------------------------------------------------
-- [023] 漏斗分析·转化 | 电商 | 浏览-加购-下单-支付全链路转化漏斗
-- ------------------------------------------------------------------------------
WITH f AS (
    SELECT 'view'     AS step, 1 AS step_no, COUNT(DISTINCT session_id) AS cnt
    FROM user_events WHERE event_type = 'view'
    UNION ALL
    SELECT 'cart',  2, COUNT(DISTINCT session_id) FROM user_events WHERE event_type = 'add_cart'
    UNION ALL
    SELECT 'order', 3, COUNT(DISTINCT session_id) FROM user_events WHERE event_type = 'place_order'
    UNION ALL
    SELECT 'pay',   4, COUNT(DISTINCT session_id) FROM user_events WHERE event_type = 'pay'
)
SELECT step, step_no, cnt,
       LAG(cnt) OVER (ORDER BY step_no) AS prev_cnt,
       ROUND(cnt / NULLIF(LAG(cnt) OVER (ORDER BY step_no), 0) * 100, 2) AS step_conv_pct,
       ROUND(cnt / NULLIF(FIRST_VALUE(cnt) OVER (ORDER BY step_no), 0) * 100, 2) AS total_conv_pct
FROM f
ORDER BY step_no;

-- ------------------------------------------------------------------------------
-- [024] 窗口函数·LEAD | 电商 | 用户首单到二单的时间间隔分布
-- ------------------------------------------------------------------------------
WITH ranked AS (
    SELECT customer_id, order_id, order_date, pay_amount,
           ROW_NUMBER() OVER (PARTITION BY customer_id ORDER BY order_date) AS seq,
           LEAD(order_date) OVER (PARTITION BY customer_id ORDER BY order_date) AS next_order_date
    FROM orders
    WHERE status = 'completed'
)
SELECT CASE WHEN seq = 1 AND next_order_date IS NOT NULL THEN 'has_second' ELSE 'one_time' END AS cust_kind,
       COUNT(*) AS cnt,
       ROUND(AVG((next_order_date - order_date)), 2) AS avg_gap_days,
       MIN((next_order_date - order_date)) AS min_gap_days,
       MAX((next_order_date - order_date)) AS max_gap_days
FROM ranked
WHERE seq = 1
GROUP BY CASE WHEN seq = 1 AND next_order_date IS NOT NULL THEN 'has_second' ELSE 'one_time' END;

-- ------------------------------------------------------------------------------
-- [025] 多表·库存周转 | 电商 | 商品库存周转天数与安全库存预警
-- ------------------------------------------------------------------------------
WITH sold AS (
    SELECT oi.product_id,
           SUM(oi.quantity) AS qty_sold,
           SUM(oi.amount)   AS amt_sold
    FROM order_items oi
    JOIN orders o ON oi.order_id = o.order_id
    WHERE o.status = 'completed'
      AND o.order_date >= (TRUNC(SYSDATE) - 90)
    GROUP BY oi.product_id
),
inv AS (
    SELECT product_id, SUM(quantity) AS stock_qty, SUM(safety_stock) AS safety_qty
    FROM inventory
    GROUP BY product_id
)
SELECT p.product_id, p.product_name,
       COALESCE(s.qty_sold, 0) AS qty_sold_90d,
       COALESCE(i.stock_qty, 0) AS stock_qty,
       COALESCE(i.safety_qty, 0) AS safety_qty,
       ROUND(COALESCE(i.stock_qty, 0)
             / NULLIF(COALESCE(s.qty_sold, 0) / 90.0, 0), 1) AS turnover_days,
       CASE WHEN COALESCE(i.stock_qty, 0) <= COALESCE(i.safety_qty, 0) THEN 'under_safety'
            WHEN COALESCE(i.stock_qty, 0) > COALESCE(s.qty_sold, 0) THEN 'overstock'
            ELSE 'normal' END AS stock_status
FROM products p
LEFT JOIN sold s ON p.product_id = s.product_id
LEFT JOIN inv  i ON p.product_id = i.product_id
WHERE p.status = 'on_sale'
ORDER BY turnover_days DESC;

-- ------------------------------------------------------------------------------
-- [026] 左连接·滞销识别 | 电商 | 滞销商品识别（上架后长期无销量）
-- ------------------------------------------------------------------------------
WITH last_sale AS (
    SELECT oi.product_id, MAX(o.order_date) AS last_sold_date, SUM(oi.quantity) AS total_qty
    FROM order_items oi
    JOIN orders o ON oi.order_id = o.order_id
    WHERE o.status = 'completed'
    GROUP BY oi.product_id
)
SELECT p.product_id, p.product_name, c.category_name, p.launch_date,
       ls.last_sold_date,
       COALESCE(ls.total_qty, 0) AS total_qty,
       (TRUNC(SYSDATE) - COALESCE(ls.last_sold_date, p.launch_date)) AS idle_days,
       CASE WHEN ls.product_id IS NULL THEN 'never_sold'
            WHEN (TRUNC(SYSDATE) - ls.last_sold_date) > 90 THEN 'long_idle'
            ELSE 'active' END AS sale_status
FROM products p
LEFT JOIN last_sale ls ON p.product_id = ls.product_id
LEFT JOIN categories c ON p.category_id = c.category_id
WHERE p.status = 'on_sale'
  AND (ls.product_id IS NULL OR (TRUNC(SYSDATE) - ls.last_sold_date) > 90)
ORDER BY idle_days DESC;

-- ------------------------------------------------------------------------------
-- [027] 时间分段·生命周期 | 电商 | 商品生命周期阶段划分（新品/成长/成熟/衰退）
-- ------------------------------------------------------------------------------
WITH pm AS (
    SELECT oi.product_id, TO_CHAR(o.order_date, 'YYYY-MM') AS ym, SUM(oi.amount) AS amt
    FROM order_items oi
    JOIN orders o ON oi.order_id = o.order_id
    WHERE o.status = 'completed'
    GROUP BY oi.product_id, TO_CHAR(o.order_date, 'YYYY-MM')
),
with_trend AS (
    SELECT product_id, ym, amt,
           LAG(amt, 1) OVER (PARTITION BY product_id ORDER BY ym) AS amt_m1,
           LAG(amt, 2) OVER (PARTITION BY product_id ORDER BY ym) AS amt_m2
    FROM pm
),
agg AS (
    SELECT product_id, SUM(amt) AS total_amt, COUNT(*) AS active_months,
           SUM(CASE WHEN amt > COALESCE(amt_m1, 0) THEN 1 ELSE 0 END) AS up_months
    FROM with_trend
    GROUP BY product_id
)
SELECT p.product_name, a.total_amt, a.active_months, a.up_months,
       ROUND(a.up_months / NULLIF(a.active_months, 0) * 100, 2) AS up_ratio_pct,
       CASE WHEN a.active_months <= 3 THEN 'new'
            WHEN a.up_months / NULLIF(a.active_months, 0) >= 0.6 THEN 'growth'
            WHEN a.up_months / NULLIF(a.active_months, 0) >= 0.3 THEN 'mature'
            ELSE 'decline' END AS life_stage
FROM agg a
JOIN products p ON a.product_id = p.product_id
ORDER BY a.total_amt DESC;

-- ------------------------------------------------------------------------------
-- [028] 统计·相关性 | 电商 | 商品评分区间与销量/价格相关性分析
-- ------------------------------------------------------------------------------
WITH pr AS (
    SELECT p.product_id, p.price,
           AVG(r.rating) AS avg_rating,
           COUNT(r.review_id) AS review_cnt,
           SUM(oi.quantity) AS qty_sold
    FROM products p
    LEFT JOIN reviews     r  ON p.product_id = r.product_id
    LEFT JOIN order_items oi ON p.product_id = oi.product_id
    GROUP BY p.product_id, p.price
)
SELECT CASE WHEN avg_rating >= 4.5 THEN '4.5+'
            WHEN avg_rating >= 4.0 THEN '4.0-4.5'
            WHEN avg_rating >= 3.0 THEN '3.0-4.0'
            ELSE '<3.0' END AS rating_band,
       COUNT(*)                    AS product_cnt,
       ROUND(AVG(qty_sold), 1)     AS avg_qty_sold,
       ROUND(AVG(price), 2)        AS avg_price,
       ROUND(AVG(review_cnt), 1)   AS avg_review_cnt,
       ROUND(AVG(avg_rating) * AVG(qty_sold), 1) AS rating_qty_prod
FROM pr
WHERE avg_rating IS NOT NULL
GROUP BY CASE WHEN avg_rating >= 4.5 THEN '4.5+'
              WHEN avg_rating >= 4.0 THEN '4.0-4.5'
              WHEN avg_rating >= 3.0 THEN '3.0-4.0'
              ELSE '<3.0' END
ORDER BY rating_band DESC;

-- ------------------------------------------------------------------------------
-- [029] 窗口·缺货损失 | 电商 | 低库存缺货损失估算（按日销量外推）
-- ------------------------------------------------------------------------------
WITH daily AS (
    SELECT oi.product_id, CAST(o.order_date AS DATE) AS dt, SUM(oi.quantity) AS qty
    FROM order_items oi
    JOIN orders o ON oi.order_id = o.order_id
    WHERE o.status = 'completed'
      AND o.order_date >= (TRUNC(SYSDATE) - 30)
    GROUP BY oi.product_id, CAST(o.order_date AS DATE)
),
rate AS (
    SELECT product_id, AVG(qty) AS avg_daily_qty, COUNT(*) AS sale_days
    FROM daily GROUP BY product_id
),
cur AS (
    SELECT i.product_id, SUM(i.quantity) AS stock, SUM(i.safety_stock) AS safety
    FROM inventory i GROUP BY i.product_id
)
SELECT p.product_name, r.avg_daily_qty, c.stock,
       ROUND(COALESCE(c.stock, 0) / NULLIF(r.avg_daily_qty, 0), 1) AS days_cover,
       CASE WHEN COALESCE(c.stock, 0) <= 0 THEN ROUND(r.avg_daily_qty * 7 * p.price, 2)
            WHEN COALESCE(c.stock, 0) / NULLIF(r.avg_daily_qty, 0) < 3
                 THEN ROUND((3 * r.avg_daily_qty - c.stock) * p.price, 2)
            ELSE 0 END AS est_loss_amt
FROM rate r
JOIN cur c ON r.product_id = c.product_id
JOIN products p ON r.product_id = p.product_id
WHERE COALESCE(c.stock, 0) / NULLIF(r.avg_daily_qty, 0) < 7
ORDER BY est_loss_amt DESC
FETCH FIRST 100 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [030] 漏斗·购物车 | 电商 | 购物车加购-支付转化与放弃分析
-- ------------------------------------------------------------------------------
WITH cart AS (
    SELECT customer_id, product_id, add_time, is_purchased, quantity
    FROM carts
    WHERE add_time >= (TRUNC(SYSDATE) - 30)
),
agg AS (
    SELECT p.category_id,
           COUNT(*)                                          AS add_cnt,
           SUM(CASE WHEN is_purchased = 1 THEN 1 ELSE 0 END) AS buy_cnt,
           SUM(CASE WHEN is_purchased = 1 THEN quantity ELSE 0 END) AS buy_qty
    FROM cart
    JOIN products p ON cart.product_id = p.product_id
    GROUP BY p.category_id
)
SELECT c.category_name, a.add_cnt, a.buy_cnt,
       ROUND(a.buy_cnt / NULLIF(a.add_cnt, 0) * 100, 2) AS conv_pct,
       ROUND(100 - a.buy_cnt / NULLIF(a.add_cnt, 0) * 100, 2) AS abandon_pct,
       a.buy_qty
FROM agg a
JOIN categories c ON a.category_id = c.category_id
ORDER BY abandon_pct DESC;

-- ------------------------------------------------------------------------------
-- [031] RFM模型 | 电商 | 用户 RFM 三维评分与价值分层
-- ------------------------------------------------------------------------------
WITH rfm AS (
    SELECT o.customer_id,
           (TRUNC(SYSDATE) - MAX(o.order_date))  AS recency,
           COUNT(DISTINCT o.order_id)                 AS frequency,
           SUM(o.pay_amount)                          AS monetary
    FROM orders o
    WHERE o.status = 'completed'
    GROUP BY o.customer_id
),
scored AS (
    SELECT customer_id, recency, frequency, monetary,
           NTILE(5) OVER (ORDER BY recency DESC)  AS r_score,
           NTILE(5) OVER (ORDER BY frequency)     AS f_score,
           NTILE(5) OVER (ORDER BY monetary)      AS m_score
    FROM rfm
)
SELECT customer_id, recency, frequency, ROUND(monetary, 2) AS monetary,
       r_score, f_score, m_score,
       CASE WHEN r_score >= 4 AND f_score >= 4 AND m_score >= 4 THEN 'champion'
            WHEN r_score >= 3 AND f_score >= 3 THEN 'loyal'
            WHEN r_score >= 4 AND f_score <= 2 THEN 'new_or_promising'
            WHEN r_score <= 2 AND f_score >= 4 THEN 'at_risk'
            WHEN r_score <= 2 AND f_score <= 2 THEN 'lost'
            ELSE 'regular' END AS rfm_segment
FROM scored
ORDER BY monetary DESC
FETCH FIRST 500 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [032] Cohort留存 | 电商 | 用户注册月度 Cohort 留存矩阵
-- ------------------------------------------------------------------------------
WITH cohort AS (
    SELECT c.customer_id,
           TO_CHAR(c.register_date, 'YYYY-MM') AS cohort_ym,
           TRUNC(c.register_date, 'MM')    AS cohort_md
    FROM customers c
),
act AS (
    SELECT o.customer_id,
           TO_CHAR(o.order_date, 'YYYY-MM') AS act_ym,
           TRUNC(o.order_date, 'MM')    AS act_md
    FROM orders o
    WHERE o.status = 'completed'
    GROUP BY o.customer_id, TO_CHAR(o.order_date, 'YYYY-MM'), TRUNC(o.order_date, 'MM')
),
joined AS (
    SELECT ch.cohort_ym, ch.cohort_md, a.act_ym, a.act_md,
           COUNT(DISTINCT ch.customer_id) AS cust_cnt
    FROM cohort ch
    JOIN act a ON ch.customer_id = a.customer_id
    GROUP BY ch.cohort_ym, ch.cohort_md, a.act_ym, a.act_md
),
base AS (
    SELECT cohort_ym, cohort_md, COUNT(*) AS total_cust
    FROM cohort GROUP BY cohort_ym, cohort_md
)
SELECT j.cohort_ym,
       MONTHS_BETWEEN(j.act_md, j.cohort_md) AS month_diff,
       j.cust_cnt, b.total_cust,
       ROUND(j.cust_cnt / NULLIF(b.total_cust, 0) * 100, 2) AS retention_pct
FROM joined j
JOIN base b ON j.cohort_ym = b.cohort_ym AND j.cohort_md = b.cohort_md
ORDER BY j.cohort_ym, month_diff;

-- ------------------------------------------------------------------------------
-- [033] 窗口·流失预警 | 电商 | 用户活跃度衰减与流失预警名单
-- ------------------------------------------------------------------------------
WITH cust_act AS (
    SELECT customer_id,
           MAX(order_date)                          AS last_order_date,
           COUNT(*)                                 AS order_cnt,
           SUM(pay_amount)                          AS total_amt,
           AVG(pay_amount)                          AS avg_amt
    FROM orders
    WHERE status = 'completed'
    GROUP BY customer_id
),
hist AS (
    SELECT customer_id,
           COUNT(CASE WHEN order_date >= (TRUNC(SYSDATE) - 90)  THEN 1 END) AS cnt_90d,
           COUNT(CASE WHEN order_date >= (TRUNC(SYSDATE) - 180)
                       AND order_date <  (TRUNC(SYSDATE) - 90)  THEN 1 END) AS cnt_90_180d
    FROM orders
    WHERE status = 'completed'
    GROUP BY customer_id
)
SELECT ca.customer_id, c.customer_name, c."LEVEL",
       ca.last_order_date,
       (TRUNC(SYSDATE) - ca.last_order_date) AS idle_days,
       ca.order_cnt, ROUND(ca.total_amt, 2) AS total_amt,
       h.cnt_90d, h.cnt_90_180d,
       CASE WHEN (TRUNC(SYSDATE) - ca.last_order_date) > 180 THEN 'churned'
            WHEN (TRUNC(SYSDATE) - ca.last_order_date) > 90  THEN 'high_risk'
            WHEN h.cnt_90d < h.cnt_90_180d                        THEN 'declining'
            ELSE 'healthy' END AS churn_risk
FROM cust_act ca
JOIN hist h ON ca.customer_id = h.customer_id
JOIN customers c ON ca.customer_id = c.customer_id
WHERE (TRUNC(SYSDATE) - ca.last_order_date) > 60
ORDER BY ca.total_amt DESC
FETCH FIRST 500 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [034] 累计·LTV | 电商 | 用户生命周期价值（LTV）与价值分层
-- ------------------------------------------------------------------------------
WITH cust AS (
    SELECT o.customer_id,
           MIN(o.order_date) AS first_order,
           MAX(o.order_date) AS last_order,
           COUNT(*)          AS order_cnt,
           SUM(o.pay_amount) AS total_amt
    FROM orders o
    WHERE o.status = 'completed'
    GROUP BY o.customer_id
),
with_span AS (
    SELECT customer_id, order_cnt, total_amt,
           MONTHS_BETWEEN(last_order, first_order) AS life_months,
           (TRUNC(SYSDATE) - first_order)    AS days_since_first
    FROM cust
)
SELECT customer_id, order_cnt, ROUND(total_amt, 2) AS ltv,
       life_months,
       ROUND(total_amt / NULLIF(order_cnt, 0), 2)                     AS avg_order_value,
       ROUND(order_cnt / NULLIF(EXTRACT(YEAR FROM TRUNC(SYSDATE)) - 2020 + 1, 0), 2) AS annual_frequency,
       ROUND(total_amt / NULLIF(life_months, 0), 2)                   AS monthly_value,
       CASE WHEN total_amt >= 10000 THEN 'platinum'
            WHEN total_amt >= 3000  THEN 'gold'
            WHEN total_amt >= 800   THEN 'silver'
            ELSE 'bronze' END AS value_tier
FROM with_span
ORDER BY ltv DESC
FETCH FIRST 300 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [035] 二八法则 | 电商 | 高价值用户识别与销售集中度分析
-- ------------------------------------------------------------------------------
WITH cust AS (
    SELECT customer_id, SUM(pay_amount) AS amt
    FROM orders WHERE status = 'completed' GROUP BY customer_id
),
ordered AS (
    SELECT customer_id, amt,
           ROW_NUMBER() OVER (ORDER BY amt DESC) AS rn,
           COUNT(*) OVER ()                      AS total_cust,
           SUM(amt) OVER (ORDER BY amt DESC
                ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS cum_amt,
           SUM(amt) OVER ()                                        AS total_amt
    FROM cust
)
SELECT customer_id, ROUND(amt, 2) AS amt, rn,
       ROUND(rn / NULLIF(total_cust, 0) * 100, 2)       AS cust_pct,
       ROUND(cum_amt / NULLIF(total_amt, 0) * 100, 2)   AS cum_gmv_pct,
       CASE WHEN cum_amt / NULLIF(total_amt, 0) <= 0.8 THEN 'top80_contributor' ELSE 'rest' END AS pareto_flag
FROM ordered
WHERE cum_amt / NULLIF(total_amt, 0) <= 0.9001
ORDER BY rn;

-- ------------------------------------------------------------------------------
-- [036] 聚合·品类广度 | 电商 | 用户购买品类广度与交叉销售机会
-- ------------------------------------------------------------------------------
WITH cu AS (
    SELECT o.customer_id, COUNT(DISTINCT p.category_id) AS cat_cnt,
           SUM(o.pay_amount) AS total_amt
    FROM orders o
    JOIN order_items oi ON o.order_id = oi.order_id
    JOIN products    p  ON oi.product_id = p.product_id
    WHERE o.status = 'completed'
    GROUP BY o.customer_id
),
total_cat AS (SELECT COUNT(*) AS all_cat FROM categories)
SELECT cat_cnt AS category_breadth,
       COUNT(*) AS cust_cnt,
       ROUND(AVG(total_amt), 2) AS avg_amt,
       ROUND(SUM(total_amt) / NULLIF(SUM(SUM(total_amt)) OVER (), 0) * 100, 2) AS gmv_share_pct
FROM cu
GROUP BY cat_cnt
ORDER BY cat_cnt;

-- ------------------------------------------------------------------------------
-- [037] 状态转移·等级迁移 | 电商 | 会员等级迁移矩阵（年初 vs 当前）
-- ------------------------------------------------------------------------------
WITH level_begin AS (
    SELECT customer_id, begin_level
    FROM (
        SELECT customer_id, new_level AS begin_level,
               ROW_NUMBER() OVER (PARTITION BY customer_id ORDER BY change_date) AS rn
        FROM cust_level_log
        WHERE change_date >= DATE '2024-01-01'
    ) t
    WHERE rn = 1
),
cust_amt AS (
    SELECT customer_id, SUM(pay_amount) AS total_amt, COUNT(*) AS order_cnt
    FROM orders
    WHERE status = 'completed'
    GROUP BY customer_id
)
SELECT COALESCE(lb.begin_level, cu."LEVEL") AS begin_level,
       cu."LEVEL"                           AS current_level,
       COUNT(*)                           AS cust_cnt,
       ROUND(AVG(ca.total_amt), 2)        AS avg_amt,
       ROUND(SUM(ca.total_amt), 2)        AS total_gmv,
       CASE WHEN COALESCE(lb.begin_level, cu."LEVEL") = cu."LEVEL" THEN 'stable'
            ELSE 'changed' END            AS move_flag
FROM customers cu
LEFT JOIN level_begin lb ON cu.customer_id = lb.customer_id
LEFT JOIN cust_amt    ca ON cu.customer_id = ca.customer_id
GROUP BY COALESCE(lb.begin_level, cu."LEVEL"), cu."LEVEL",
         CASE WHEN COALESCE(lb.begin_level, cu."LEVEL") = cu."LEVEL" THEN 'stable' ELSE 'changed' END
ORDER BY cust_cnt DESC;

-- ------------------------------------------------------------------------------
-- [038] 时间窗口·唤醒 | 电商 | 沉睡用户识别与唤醒效果评估
-- ------------------------------------------------------------------------------
WITH act AS (
    SELECT customer_id, MAX(order_date) AS last_dt, COUNT(*) AS cnt, SUM(pay_amount) AS amt
    FROM orders WHERE status = 'completed' GROUP BY customer_id
),
sleeping AS (
    SELECT customer_id, last_dt, cnt, amt
    FROM act
    WHERE (TRUNC(SYSDATE) - last_dt) BETWEEN 90 AND 365
),
reactivated AS (
    SELECT s.customer_id, MIN(o.order_date) AS wake_date
    FROM sleeping s
    JOIN orders o ON s.customer_id = o.customer_id AND o.order_date > s.last_dt
    WHERE o.status = 'completed'
    GROUP BY s.customer_id
)
SELECT CASE WHEN r.customer_id IS NOT NULL THEN 'reactivated' ELSE 'still_sleeping' END AS wake_status,
       COUNT(*)                          AS cust_cnt,
       ROUND(AVG((COALESCE(r.wake_date, TRUNC(SYSDATE)) - s.last_dt)), 1) AS avg_sleep_days,
       ROUND(SUM(s.amt), 2)              AS hist_amt
FROM sleeping s
LEFT JOIN reactivated r ON s.customer_id = r.customer_id
GROUP BY CASE WHEN r.customer_id IS NOT NULL THEN 'reactivated' ELSE 'still_sleeping' END;

-- ------------------------------------------------------------------------------
-- [039] FIRST_VALUE·归因 | 电商 | 用户首单渠道归因与后续渠道偏好
-- ------------------------------------------------------------------------------
WITH ordered AS (
    SELECT customer_id, order_id, order_date, channel, pay_amount,
           ROW_NUMBER() OVER (PARTITION BY customer_id ORDER BY order_date) AS seq,
           FIRST_VALUE(channel) OVER (PARTITION BY customer_id ORDER BY order_date) AS first_channel
    FROM orders
    WHERE status = 'completed'
)
SELECT first_channel AS attribution_channel,
       COUNT(DISTINCT customer_id) AS cust_cnt,
       COUNT(*)                    AS order_cnt,
       SUM(pay_amount)             AS gmv,
       SUM(CASE WHEN seq = 1 THEN pay_amount ELSE 0 END) AS first_order_gmv,
       ROUND(AVG(pay_amount), 2)   AS avg_order_amt,
       COUNT(DISTINCT CASE WHEN seq > 1 THEN channel END) AS later_channel_variety
FROM ordered
GROUP BY first_channel
ORDER BY gmv DESC;

-- ------------------------------------------------------------------------------
-- [040] LEAD·路径分析 | 电商 | 用户品类购买路径（下一个购买品类）流转
-- ------------------------------------------------------------------------------
WITH uc AS (
    SELECT o.customer_id, o.order_date, p.category_id,
           ROW_NUMBER() OVER (PARTITION BY o.customer_id ORDER BY o.order_date, o.order_id) AS seq
    FROM orders o
    JOIN order_items oi ON o.order_id = oi.order_id
    JOIN products    p  ON oi.product_id = p.product_id
    WHERE o.status = 'completed'
),
path AS (
    SELECT a.category_id AS from_cat,
           LEAD(b.category_id) OVER (PARTITION BY a.customer_id ORDER BY a.seq) AS to_cat
    FROM uc a
    LEFT JOIN uc b ON a.customer_id = b.customer_id AND b.seq = a.seq + 1
)
SELECT ca.category_name AS from_category,
       cb.category_name AS to_category,
       COUNT(*) AS transition_cnt
FROM path
JOIN categories ca ON path.from_cat = ca.category_id
JOIN categories cb ON path.to_cat = cb.category_id
GROUP BY ca.category_name, cb.category_name
HAVING COUNT(*) >= 5
ORDER BY transition_cnt DESC
FETCH FIRST 100 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [041] HAVING·跨店行为 | 电商 | 跨门店/跨城市购买用户识别
-- ------------------------------------------------------------------------------
SELECT c.customer_id, c.customer_name,
       COUNT(DISTINCT s.store_id) AS store_cnt,
       COUNT(DISTINCT s.city)     AS city_cnt,
       COUNT(*)                   AS order_cnt,
       SUM(o.pay_amount)          AS total_amt,
       LISTAGG(DISTINCT s.city, ',') WITHIN GROUP (ORDER BY s.city) AS cities
FROM orders o
JOIN customers c ON o.customer_id = c.customer_id
JOIN stores    s ON o.store_id = s.store_id
WHERE o.status = 'completed'
  AND o.order_date >= (TRUNC(SYSDATE) - 365)
GROUP BY c.customer_id, c.customer_name
HAVING COUNT(DISTINCT s.store_id) > 3
ORDER BY store_cnt DESC, total_amt DESC
FETCH FIRST 200 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [042] 日期函数·营销名单 | 电商 | 生日当月营销名单与偏好品类推荐
-- ------------------------------------------------------------------------------
SELECT c.customer_id, c.customer_name, c.birth_date, c.city,
       EXTRACT(MONTH FROM TRUNC(SYSDATE)) AS cur_month,
       ROUND((TRUNC(SYSDATE) - c.register_date) / 365.0, 1) AS reg_years,
       MAX(o.order_date) AS last_order_date,
       SUM(o.pay_amount) AS hist_amt
FROM customers c
LEFT JOIN orders o ON c.customer_id = o.customer_id AND o.status = 'completed'
WHERE EXTRACT(MONTH FROM c.birth_date) = EXTRACT(MONTH FROM TRUNC(SYSDATE))
GROUP BY c.customer_id, c.customer_name, c.birth_date, c.city, c.register_date
ORDER BY hist_amt DESC;

-- ------------------------------------------------------------------------------
-- [043] 异常检测·统计 | 电商 | 订单金额异常检测（偏离个人历史均值）
-- ------------------------------------------------------------------------------
WITH cust_stat AS (
    SELECT customer_id,
           AVG(pay_amount) AS avg_amt,
           COUNT(*)        AS cnt
    FROM orders
    WHERE status = 'completed'
    GROUP BY customer_id
    HAVING COUNT(*) >= 5
),
tagged AS (
    SELECT o.order_id, o.customer_id, o.pay_amount, o.order_date,
           s.avg_amt,
           (o.pay_amount - s.avg_amt) / NULLIF(s.avg_amt, 0) AS deviation
    FROM orders o
    JOIN cust_stat s ON o.customer_id = s.customer_id
    WHERE o.status = 'completed'
      AND o.order_date >= (TRUNC(SYSDATE) - 30)
)
SELECT order_id, customer_id, ROUND(pay_amount, 2) AS pay_amount,
       ROUND(avg_amt, 2) AS hist_avg_amt,
       ROUND(deviation * 100, 1) AS deviation_pct,
       CASE WHEN deviation >= 3 THEN 'suspect_high'
            WHEN deviation <= -0.8 THEN 'suspect_low'
            ELSE 'normal' END AS anomaly_flag
FROM tagged
WHERE ABS(deviation) >= 3
ORDER BY ABS(deviation) DESC
FETCH FIRST 200 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [044] 对账·差异检测 | 电商 | 订单金额与支付流水对账差异核查
-- ------------------------------------------------------------------------------
WITH o_agg AS (
    SELECT order_id, SUM(pay_amount) AS order_amt, COUNT(*) AS cnt
    FROM orders WHERE status = 'completed' GROUP BY order_id
),
p_agg AS (
    SELECT order_id, SUM(pay_amount) AS paid_amt, COUNT(*) AS pay_cnt
    FROM payments WHERE status = 'success' GROUP BY order_id
),
all_orders AS (
    SELECT order_id FROM o_agg
    UNION
    SELECT order_id FROM p_agg
)
SELECT a.order_id,
       COALESCE(oa.order_amt, 0) AS order_amt,
       COALESCE(pa.paid_amt, 0)  AS paid_amt,
       COALESCE(oa.order_amt, 0) - COALESCE(pa.paid_amt, 0) AS diff_amt,
       CASE WHEN oa.order_id IS NULL THEN 'pay_only'
            WHEN pa.order_id IS NULL THEN 'order_only'
            WHEN ABS(COALESCE(oa.order_amt, 0) - COALESCE(pa.paid_amt, 0)) > 0.01 THEN 'amount_mismatch'
            ELSE 'matched' END AS recon_status
FROM all_orders a
LEFT JOIN o_agg oa ON a.order_id = oa.order_id
LEFT JOIN p_agg pa ON a.order_id = pa.order_id
WHERE oa.order_id IS NULL OR pa.order_id IS NULL
   OR ABS(COALESCE(oa.order_amt, 0) - COALESCE(pa.paid_amt, 0)) > 0.01
ORDER BY diff_amt DESC;

-- ------------------------------------------------------------------------------
-- [045] 日期差·物流异常 | 电商 | 超时未签收订单与物流节点停滞监控
-- ------------------------------------------------------------------------------
WITH latest AS (
    SELECT order_id, MAX(node_time) AS last_node_time, MAX(node_name) AS last_node
    FROM logistics_nodes
    GROUP BY order_id
)
SELECT o.order_id, o.customer_id,
       o.order_date,
       l.last_node_time,
       ((SYSDATE - l.last_node_time) * 24) AS stall_hours,
       l.last_node,
       CASE WHEN ((SYSDATE - l.last_node_time) * 24) > 72 THEN 'severely_stalled'
            WHEN ((SYSDATE - l.last_node_time) * 24) > 48 THEN 'stalled'
            ELSE 'in_transit' END AS logistic_status
FROM orders o
JOIN latest l ON o.order_id = l.order_id
WHERE o.status IN ('shipped', 'delivering')
  AND ((SYSDATE - l.last_node_time) * 24) > 24
ORDER BY stall_hours DESC
FETCH FIRST 300 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [046] 聚合·风险识别 | 电商 | 多收货地址异常用户识别（刷单风险）
-- ------------------------------------------------------------------------------
WITH addr AS (
    SELECT o.customer_id, s.province AS ship_province, s.city AS ship_city,
           COUNT(*) AS order_cnt, SUM(o.pay_amount) AS amt
    FROM orders o
    JOIN shipments s ON o.order_id = s.order_id
    WHERE o.order_date >= (TRUNC(SYSDATE) - 90)
    GROUP BY o.customer_id, s.province, s.city
),
cust AS (
    SELECT customer_id,
           COUNT(*)                AS city_cnt,
           SUM(order_cnt)          AS total_orders,
           SUM(amt)                AS total_amt,
           MAX(order_cnt)          AS max_city_orders
    FROM addr GROUP BY customer_id
)
SELECT c.customer_id, cu.customer_name,
       c.city_cnt, c.total_orders, ROUND(c.total_amt, 2) AS total_amt,
       ROUND(c.max_city_orders / NULLIF(c.total_orders, 0) * 100, 2) AS top_city_concentration_pct,
       CASE WHEN c.city_cnt >= 8 THEN 'high_risk'
            WHEN c.city_cnt >= 4 THEN 'medium_risk'
            ELSE 'low_risk' END AS risk_level
FROM cust c
JOIN customers cu ON c.customer_id = cu.customer_id
WHERE c.city_cnt >= 3
ORDER BY c.city_cnt DESC
FETCH FIRST 200 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [047] 阈值·退货识别 | 电商 | 高频退货用户与恶意退货嫌疑识别
-- ------------------------------------------------------------------------------
WITH cust_order AS (
    SELECT customer_id, COUNT(*) AS order_cnt, SUM(pay_amount) AS total_amt
    FROM orders WHERE status = 'completed' GROUP BY customer_id
),
cust_refund AS (
    SELECT o.customer_id,
           COUNT(*)             AS refund_cnt,
           SUM(r.refund_amount) AS refund_amt
    FROM refunds r
    JOIN orders o ON r.order_id = o.order_id
    GROUP BY o.customer_id
)
SELECT co.customer_id, c.customer_name,
       co.order_cnt,
       COALESCE(cr.refund_cnt, 0) AS refund_cnt,
       COALESCE(cr.refund_amt, 0) AS refund_amt,
       ROUND(COALESCE(cr.refund_cnt, 0) / NULLIF(co.order_cnt, 0) * 100, 2) AS refund_rate_pct,
       ROUND(COALESCE(cr.refund_amt, 0) / NULLIF(co.total_amt, 0) * 100, 2) AS refund_amt_rate_pct,
       CASE WHEN COALESCE(cr.refund_cnt, 0) / NULLIF(co.order_cnt, 0) > 0.5
                 AND COALESCE(cr.refund_cnt, 0) >= 5 THEN 'blacklist_candidate'
            WHEN COALESCE(cr.refund_cnt, 0) / NULLIF(co.order_cnt, 0) > 0.3 THEN 'watch'
            ELSE 'normal' END AS abuse_flag
FROM cust_order co
LEFT JOIN cust_refund cr ON co.customer_id = cr.customer_id
JOIN customers c ON co.customer_id = c.customer_id
WHERE COALESCE(cr.refund_cnt, 0) >= 3
ORDER BY refund_rate_pct DESC
FETCH FIRST 200 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [048] 对比·目标达成 | 电商 | 门店月度销售目标达成率与缺口
-- ------------------------------------------------------------------------------
WITH actual AS (
    SELECT s.store_id, s.store_name, s.region,
           SUM(o.pay_amount) AS gmv,
           COUNT(*)          AS order_cnt
    FROM stores s
    LEFT JOIN orders o ON s.store_id = o.store_id
         AND o.status = 'completed'
         AND TO_CHAR(o.order_date, 'YYYY-MM') = TO_CHAR(TRUNC(TRUNC(SYSDATE), 'MM'), 'YYYY-MM')
    GROUP BY s.store_id, s.store_name, s.region
),
target AS (
    SELECT store_id, SUM(budget) AS target_amt
    FROM store_targets
    WHERE TO_CHAR(target_month, 'YYYY-MM') = TO_CHAR(TRUNC(TRUNC(SYSDATE), 'MM'), 'YYYY-MM')
    GROUP BY store_id
)
SELECT a.store_name, a.region,
       COALESCE(a.gmv, 0) AS gmv,
       COALESCE(t.target_amt, 0) AS target_amt,
       ROUND(COALESCE(a.gmv, 0) / NULLIF(t.target_amt, 0) * 100, 2) AS achieve_pct,
       ROUND(COALESCE(t.target_amt, 0) - COALESCE(a.gmv, 0), 2)     AS gap_amt,
       CASE WHEN COALESCE(a.gmv, 0) >= COALESCE(t.target_amt, 0) THEN 'achieved'
            WHEN COALESCE(a.gmv, 0) >= COALESCE(t.target_amt, 0) * 0.9 THEN 'near'
            ELSE 'behind' END AS status_flag
FROM actual a
LEFT JOIN target t ON a.store_id = t.store_id
ORDER BY achieve_pct DESC;

-- ------------------------------------------------------------------------------
-- [049] 订单内分析·连带率 | 电商 | 订单商品连带率（每单平均商品数与品类数）
-- ------------------------------------------------------------------------------
WITH oi AS (
    SELECT o.order_id,
           COUNT(DISTINCT oi.product_id)  AS sku_cnt,
           COUNT(DISTINCT p.category_id)  AS cat_cnt,
           SUM(oi.quantity)               AS qty,
           SUM(oi.amount)                 AS amt
    FROM orders o
    JOIN order_items oi ON o.order_id = oi.order_id
    JOIN products    p  ON oi.product_id = p.product_id
    WHERE o.status = 'completed'
      AND o.order_date >= (TRUNC(SYSDATE) - 90)
    GROUP BY o.order_id
)
SELECT CASE WHEN sku_cnt = 1 THEN 'single_sku'
            WHEN sku_cnt BETWEEN 2 AND 3 THEN '2-3_sku'
            WHEN sku_cnt BETWEEN 4 AND 6 THEN '4-6_sku'
            ELSE '7+_sku' END AS sku_group,
       COUNT(*)                      AS order_cnt,
       ROUND(AVG(sku_cnt), 2)        AS avg_sku,
       ROUND(AVG(cat_cnt), 2)        AS avg_cat,
       ROUND(AVG(qty), 2)            AS avg_qty,
       ROUND(AVG(amt), 2)            AS avg_amt,
       ROUND(AVG(amt) / NULLIF(AVG(qty), 0), 2) AS avg_unit_price
FROM oi
GROUP BY CASE WHEN sku_cnt = 1 THEN 'single_sku'
              WHEN sku_cnt BETWEEN 2 AND 3 THEN '2-3_sku'
              WHEN sku_cnt BETWEEN 4 AND 6 THEN '4-6_sku'
              ELSE '7+_sku' END
ORDER BY avg_amt DESC;

-- ------------------------------------------------------------------------------
-- [050] 预测·移动平均 | 电商 | 基于历史移动平均的日销售预测与残差
-- ------------------------------------------------------------------------------
WITH daily AS (
    SELECT CAST(order_date AS DATE) AS dt, SUM(pay_amount) AS gmv
    FROM orders
    WHERE status = 'completed'
      AND order_date >= (TRUNC(SYSDATE) - 120)
    GROUP BY CAST(order_date AS DATE)
),
ma AS (
    SELECT dt, gmv,
           AVG(gmv) OVER (ORDER BY dt ROWS BETWEEN 7 PRECEDING AND 1 PRECEDING)  AS pred_ma7,
           AVG(gmv) OVER (ORDER BY dt ROWS BETWEEN 14 PRECEDING AND 1 PRECEDING) AS pred_ma14
    FROM daily
)
SELECT dt, ROUND(gmv, 2) AS actual_gmv,
       ROUND(pred_ma7, 2)  AS pred_ma7,
       ROUND(pred_ma14, 2) AS pred_ma14,
       ROUND(gmv - pred_ma7, 2)                                        AS resid_ma7,
       ROUND(ABS(gmv - pred_ma7) / NULLIF(gmv, 0) * 100, 2)            AS abs_err_pct_ma7,
       ROUND(AVG(ABS(gmv - pred_ma7) / NULLIF(gmv, 0) * 100)
             OVER (ORDER BY dt ROWS BETWEEN 29 PRECEDING AND CURRENT ROW), 2) AS mape_30d
FROM ma
WHERE pred_ma7 IS NOT NULL
ORDER BY dt DESC;

-- ------------------------------------------------------------------------------
-- [051] 活跃度·DAU/MAU | 电商 | 日活/周活/月活与粘性指标（DAU/MAU）
-- ------------------------------------------------------------------------------
WITH d AS (
    SELECT CAST(event_time AS DATE) AS dt, COUNT(DISTINCT customer_id) AS dau
    FROM user_events
    WHERE event_time >= (TRUNC(SYSDATE) - 90)
    GROUP BY CAST(event_time AS DATE)
),
m AS (
    SELECT TO_CHAR(event_time, 'YYYY-MM') AS ym, COUNT(DISTINCT customer_id) AS mau
    FROM user_events
    WHERE event_time >= (TRUNC(SYSDATE) - 90)
    GROUP BY TO_CHAR(event_time, 'YYYY-MM')
)
SELECT d.dt, d.dau, m.mau,
       ROUND(d.dau / NULLIF(m.mau, 0) * 100, 2) AS stickiness_pct,
       ROUND(AVG(d.dau) OVER (ORDER BY d.dt ROWS BETWEEN 6 PRECEDING AND CURRENT ROW), 1) AS avg_dau_7d
FROM d
JOIN m ON TO_CHAR(d.dt, 'YYYY-MM') = m.ym
ORDER BY d.dt;

-- ------------------------------------------------------------------------------
-- [052] 转化·注册漏斗 | 电商 | 注册用户到首单转化周期分析
-- ------------------------------------------------------------------------------
WITH reg AS (
    SELECT customer_id, register_date, city, "LEVEL"
    FROM customers
    WHERE register_date >= (TRUNC(SYSDATE) - 180)
),
first_order AS (
    SELECT customer_id, MIN(order_date) AS first_order_date
    FROM orders
    WHERE status = 'completed'
    GROUP BY customer_id
)
SELECT TO_CHAR(r.register_date, 'YYYY-MM') AS reg_ym,
       COUNT(*)                                        AS reg_cnt,
       COUNT(fo.customer_id)                           AS converted_cnt,
       ROUND(COUNT(fo.customer_id) / NULLIF(COUNT(*), 0) * 100, 2) AS conv_pct,
       ROUND(AVG((fo.first_order_date - r.register_date)), 2) AS avg_conv_days,
       SUM(CASE WHEN (fo.first_order_date - r.register_date) <= 1 THEN 1 ELSE 0 END) AS within_1d,
       SUM(CASE WHEN (fo.first_order_date - r.register_date) <= 7 THEN 1 ELSE 0 END) AS within_7d
FROM reg r
LEFT JOIN first_order fo ON r.customer_id = fo.customer_id
GROUP BY TO_CHAR(r.register_date, 'YYYY-MM')
ORDER BY reg_ym;

-- ------------------------------------------------------------------------------
-- [053] 分布·访问频次 | 电商 | 用户访问频次分布与活跃分层
-- ------------------------------------------------------------------------------
WITH freq AS (
    SELECT customer_id, COUNT(*) AS event_cnt,
           COUNT(DISTINCT CAST(event_time AS DATE)) AS active_days
    FROM user_events
    WHERE event_time >= (TRUNC(SYSDATE) - 30)
    GROUP BY customer_id
)
SELECT CASE WHEN active_days >= 20 THEN 'super_active'
            WHEN active_days >= 10 THEN 'active'
            WHEN active_days >= 4  THEN 'normal'
            WHEN active_days >= 1  THEN 'low'
            ELSE 'inactive' END AS active_level,
       COUNT(*)                      AS cust_cnt,
       ROUND(AVG(event_cnt), 1)      AS avg_events,
       ROUND(AVG(active_days), 1)    AS avg_active_days,
       ROUND(AVG(event_cnt) / NULLIF(AVG(active_days), 0), 1) AS events_per_day
FROM freq
GROUP BY CASE WHEN active_days >= 20 THEN 'super_active'
              WHEN active_days >= 10 THEN 'active'
              WHEN active_days >= 4  THEN 'normal'
              WHEN active_days >= 1  THEN 'low'
              ELSE 'inactive' END
ORDER BY avg_active_days DESC;

-- ------------------------------------------------------------------------------
-- [054] 条件聚合·设备偏好 | 电商 | 用户设备与渠道偏好交叉分析
-- ------------------------------------------------------------------------------
SELECT device,
       COUNT(DISTINCT customer_id) AS uv,
       COUNT(*)                    AS pv,
       ROUND(COUNT(*) / NULLIF(COUNT(DISTINCT customer_id), 0), 1) AS pv_per_uv,
       SUM(CASE WHEN event_type = 'view'       THEN 1 ELSE 0 END) AS view_cnt,
       SUM(CASE WHEN event_type = 'add_cart'   THEN 1 ELSE 0 END) AS cart_cnt,
       SUM(CASE WHEN event_type = 'place_order' THEN 1 ELSE 0 END) AS order_cnt,
       ROUND(SUM(CASE WHEN event_type = 'place_order' THEN 1 ELSE 0 END)
             / NULLIF(SUM(CASE WHEN event_type = 'view' THEN 1 ELSE 0 END), 0) * 100, 2) AS view2order_pct
FROM user_events
WHERE event_time >= (TRUNC(SYSDATE) - 30)
GROUP BY device
ORDER BY uv DESC;

-- ------------------------------------------------------------------------------
-- [055] 窗口·浏览路径 | 电商 | 会话内页面浏览路径（前 N 步序列）
-- ------------------------------------------------------------------------------
WITH seq AS (
    SELECT session_id, customer_id, page_url, event_time,
           ROW_NUMBER() OVER (PARTITION BY session_id ORDER BY event_time) AS step_no,
           COUNT(*)     OVER (PARTITION BY session_id)                     AS total_steps
    FROM user_events
    WHERE event_type = 'view'
      AND event_time >= (TRUNC(SYSDATE) - 7)
)
SELECT session_id, customer_id, total_steps,
       LISTAGG(CONCAT(CONCAT(CAST(step_no AS VARCHAR(10)), ':'), page_url), '>') WITHIN GROUP (ORDER BY step_no) AS path,
       ((MAX(event_time) - MIN(event_time)) * 1440) AS session_minutes
FROM seq
WHERE step_no <= 10
GROUP BY session_id, customer_id, total_steps
HAVING COUNT(*) >= 3
ORDER BY total_steps DESC
FETCH FIRST 200 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [056] 排名·热门商品 | 电商 | 商品曝光-点击-购买转化排行
-- ------------------------------------------------------------------------------
WITH ev AS (
    SELECT product_id,
           SUM(CASE WHEN event_type = 'view'        THEN 1 ELSE 0 END) AS view_cnt,
           SUM(CASE WHEN event_type = 'add_cart'    THEN 1 ELSE 0 END) AS cart_cnt,
           SUM(CASE WHEN event_type = 'place_order' THEN 1 ELSE 0 END) AS order_cnt
    FROM user_events
    WHERE product_id IS NOT NULL
      AND event_time >= (TRUNC(SYSDATE) - 30)
    GROUP BY product_id
)
SELECT p.product_name, e.view_cnt, e.cart_cnt, e.order_cnt,
       ROUND(e.cart_cnt / NULLIF(e.view_cnt, 0) * 100, 2)  AS view2cart_pct,
       ROUND(e.order_cnt / NULLIF(e.cart_cnt, 0) * 100, 2) AS cart2order_pct,
       ROUND(e.order_cnt / NULLIF(e.view_cnt, 0) * 100, 2) AS view2order_pct,
       RANK() OVER (ORDER BY e.order_cnt DESC) AS hot_rank
FROM ev e
JOIN products p ON e.product_id = p.product_id
WHERE e.view_cnt >= 100
ORDER BY e.order_cnt DESC
FETCH FIRST 100 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [057] 窗口·停留时长 | 电商 | 商品详情页停留时长估算（相邻事件间隔）
-- ------------------------------------------------------------------------------
WITH ev AS (
    SELECT customer_id, session_id, product_id, event_time,
           LEAD(event_time) OVER (PARTITION BY session_id ORDER BY event_time) AS next_time,
           LEAD(event_type) OVER (PARTITION BY session_id ORDER BY event_time) AS next_type
    FROM user_events
    WHERE event_time >= (TRUNC(SYSDATE) - 7)
      AND product_id IS NOT NULL
)
SELECT ev.product_id, p.product_name,
       COUNT(*) AS view_events,
       ROUND(AVG(((next_time - event_time) * 1440)), 2) AS avg_stay_minutes,
       MAX(((next_time - event_time) * 1440))            AS max_stay_minutes,
       SUM(CASE WHEN next_type = 'add_cart' THEN 1 ELSE 0 END) AS led_to_cart
FROM ev
JOIN products p ON ev.product_id = p.product_id
WHERE next_time IS NOT NULL
  AND ((next_time - event_time) * 1440) BETWEEN 0 AND 60
GROUP BY ev.product_id, p.product_name
HAVING COUNT(*) >= 20
ORDER BY avg_stay_minutes DESC
FETCH FIRST 100 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [058] 会话切分 | 电商 | 基于 30 分钟不活跃切分用户会话并统计
-- ------------------------------------------------------------------------------
WITH ev AS (
    SELECT customer_id, event_time, event_type,
           LAG(event_time) OVER (PARTITION BY customer_id ORDER BY event_time) AS prev_time
    FROM user_events
    WHERE event_time >= (TRUNC(SYSDATE) - 7)
),
flag AS (
    SELECT customer_id, event_time, event_type,
           CASE WHEN prev_time IS NULL
                     OR ((event_time - prev_time) * 1440) > 30 THEN 1 ELSE 0 END AS is_new
    FROM ev
),
sess AS (
    SELECT customer_id, event_time, event_type,
           SUM(is_new) OVER (PARTITION BY customer_id ORDER BY event_time
                             ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS session_id
    FROM flag
)
SELECT customer_id, session_id,
       MIN(event_time) AS start_time,
       MAX(event_time) AS end_time,
       COUNT(*)        AS event_cnt,
       ((MAX(event_time) - MIN(event_time)) * 1440) AS duration_minutes,
       SUM(CASE WHEN event_type = 'place_order' THEN 1 ELSE 0 END) AS order_cnt
FROM sess
GROUP BY customer_id, session_id
HAVING COUNT(*) >= 3
ORDER BY event_cnt DESC
FETCH FIRST 300 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [059] 新客行为 | 电商 | 新用户首周行为完整性与引导效果
-- ------------------------------------------------------------------------------
WITH nc AS (
    SELECT customer_id, register_date
    FROM customers
    WHERE register_date >= (TRUNC(SYSDATE) - 60)
),
beh AS (
    SELECT n.customer_id,
           SUM(CASE WHEN e.event_type = 'view'        THEN 1 ELSE 0 END) AS view_cnt,
           SUM(CASE WHEN e.event_type = 'add_cart'    THEN 1 ELSE 0 END) AS cart_cnt,
           SUM(CASE WHEN e.event_type = 'place_order' THEN 1 ELSE 0 END) AS order_cnt,
           COUNT(DISTINCT e.product_id) AS browsed_products
    FROM nc n
    LEFT JOIN user_events e ON n.customer_id = e.customer_id
         AND e.event_time BETWEEN n.register_date AND (n.register_date + 7)
    GROUP BY n.customer_id
)
SELECT CASE WHEN view_cnt = 0 THEN 'no_browse'
            WHEN cart_cnt = 0 THEN 'browse_only'
            WHEN order_cnt = 0 THEN 'cart_only'
            ELSE 'purchased' END AS funnel_stage,
       COUNT(*)                    AS cust_cnt,
       ROUND(AVG(view_cnt), 1)     AS avg_views,
       ROUND(AVG(browsed_products), 1) AS avg_products
FROM beh
GROUP BY CASE WHEN view_cnt = 0 THEN 'no_browse'
              WHEN cart_cnt = 0 THEN 'browse_only'
              WHEN order_cnt = 0 THEN 'cart_only'
              ELSE 'purchased' END
ORDER BY cust_cnt DESC;

-- ------------------------------------------------------------------------------
-- [060] 聚合·评价分析 | 电商 | 商品评价分布与评分集中度
-- ------------------------------------------------------------------------------
SELECT p.product_id, p.product_name,
       COUNT(r.review_id)                                        AS review_cnt,
       ROUND(AVG(r.rating), 2)                                   AS avg_rating,
       SUM(CASE WHEN r.rating = 5 THEN 1 ELSE 0 END)             AS star5,
       SUM(CASE WHEN r.rating = 4 THEN 1 ELSE 0 END)             AS star4,
       SUM(CASE WHEN r.rating = 3 THEN 1 ELSE 0 END)             AS star3,
       SUM(CASE WHEN r.rating = 2 THEN 1 ELSE 0 END)             AS star2,
       SUM(CASE WHEN r.rating = 1 THEN 1 ELSE 0 END)             AS star1,
       ROUND(SUM(CASE WHEN r.rating >= 4 THEN 1 ELSE 0 END)
             / NULLIF(COUNT(r.review_id), 0) * 100, 2)           AS positive_pct,
       ROUND(SUM(CASE WHEN r.rating <= 2 THEN 1 ELSE 0 END)
             / NULLIF(COUNT(r.review_id), 0) * 100, 2)           AS negative_pct
FROM products p
LEFT JOIN reviews r ON p.product_id = r.product_id
GROUP BY p.product_id, p.product_name
HAVING COUNT(r.review_id) >= 10
ORDER BY negative_pct DESC, review_cnt DESC
FETCH FIRST 100 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [061] 文本·评价质量 | 电商 | 评价文本长度与评分相关性（低分长评识别）
-- ------------------------------------------------------------------------------
WITH r AS (
    SELECT review_id, product_id, customer_id, rating,
           LENGTH(content) AS content_len
    FROM reviews
    WHERE content IS NOT NULL
)
SELECT CASE WHEN rating >= 4 THEN 'positive'
            WHEN rating = 3  THEN 'neutral'
            ELSE 'negative' END AS sentiment,
       COUNT(*)                       AS review_cnt,
       ROUND(AVG(content_len), 1)     AS avg_len,
       MIN(content_len)               AS min_len,
       MAX(content_len)               AS max_len,
       SUM(CASE WHEN content_len >= 200 THEN 1 ELSE 0 END) AS long_review_cnt
FROM r
GROUP BY CASE WHEN rating >= 4 THEN 'positive'
              WHEN rating = 3  THEN 'neutral'
              ELSE 'negative' END
ORDER BY avg_len DESC;

-- ------------------------------------------------------------------------------
-- [062] 复购·同商品 | 电商 | 同商品重复购买用户与复购周期
-- ------------------------------------------------------------------------------
WITH rep AS (
    SELECT oi.product_id, o.customer_id,
           COUNT(DISTINCT o.order_id)                       AS buy_times,
           MIN(o.order_date)                                AS first_dt,
           MAX(o.order_date)                                AS last_dt,
           SUM(oi.quantity)                                 AS total_qty
    FROM orders o
    JOIN order_items oi ON o.order_id = oi.order_id
    WHERE o.status = 'completed'
    GROUP BY oi.product_id, o.customer_id
    HAVING COUNT(DISTINCT o.order_id) >= 2
)
SELECT p.product_name,
       COUNT(*)                                              AS repeat_cust_cnt,
       ROUND(AVG(rep.buy_times), 2)                          AS avg_buy_times,
       ROUND(AVG((rep.last_dt - rep.first_dt)), 1)  AS avg_cycle_days,
       ROUND(AVG(rep.total_qty), 2)                          AS avg_qty
FROM rep
JOIN products p ON rep.product_id = p.product_id
GROUP BY p.product_name
ORDER BY repeat_cust_cnt DESC
FETCH FIRST 100 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [063] 路径·消费升级 | 电商 | 用户价格带升级路径（低价到高价迁移）
-- ------------------------------------------------------------------------------
WITH cust_price AS (
    SELECT o.customer_id, TO_CHAR(o.order_date, 'YYYY-MM') AS ym,
           AVG(p.price) AS avg_price, SUM(o.pay_amount) AS amt
    FROM orders o
    JOIN order_items oi ON o.order_id = oi.order_id
    JOIN products    p  ON oi.product_id = p.product_id
    WHERE o.status = 'completed'
    GROUP BY o.customer_id, TO_CHAR(o.order_date, 'YYYY-MM')
),
band AS (
    SELECT customer_id, ym, avg_price, amt,
           CASE WHEN avg_price < 100 THEN 'low'
                WHEN avg_price < 300 THEN 'mid'
                WHEN avg_price < 800 THEN 'high'
                ELSE 'luxury' END AS price_band
    FROM cust_price
),
trans AS (
    SELECT customer_id, ym, amt, price_band,
           LAG(price_band) OVER (PARTITION BY customer_id ORDER BY ym) AS prev_band
    FROM band
)
SELECT COALESCE(prev_band, 'new') AS from_band,
       price_band                 AS to_band,
       COUNT(*)                   AS trans_cnt,
       ROUND(AVG(amt), 2)         AS avg_amt
FROM trans
WHERE prev_band IS NOT NULL
GROUP BY COALESCE(prev_band, 'new'), price_band
ORDER BY trans_cnt DESC;

-- ------------------------------------------------------------------------------
-- [064] 价格带分析 | 电商 | 品类价格带销售结构与主销价格区间
-- ------------------------------------------------------------------------------
SELECT c.category_name,
       SUM(CASE WHEN p.price < 50  THEN oi.amount ELSE 0 END) AS band_0_50,
       SUM(CASE WHEN p.price >= 50  AND p.price < 200 THEN oi.amount ELSE 0 END) AS band_50_200,
       SUM(CASE WHEN p.price >= 200 AND p.price < 500 THEN oi.amount ELSE 0 END) AS band_200_500,
       SUM(CASE WHEN p.price >= 500 AND p.price < 1000 THEN oi.amount ELSE 0 END) AS band_500_1k,
       SUM(CASE WHEN p.price >= 1000 THEN oi.amount ELSE 0 END) AS band_1k_up,
       SUM(oi.amount) AS total_amt,
       COUNT(DISTINCT p.product_id) AS sku_cnt
FROM order_items oi
JOIN products   p ON oi.product_id = p.product_id
JOIN categories c ON p.category_id = c.category_id
JOIN orders     o ON oi.order_id = o.order_id
WHERE o.status = 'completed'
  AND o.order_date >= (TRUNC(SYSDATE) - 180)
GROUP BY c.category_name
ORDER BY total_amt DESC;

-- ------------------------------------------------------------------------------
-- [065] 份额·品牌竞争 | 电商 | 同品类品牌份额与集中度（HHI 指数）
-- ------------------------------------------------------------------------------
WITH brand AS (
    SELECT p.category_id, p.brand, SUM(oi.amount) AS amt
    FROM order_items oi
    JOIN products p ON oi.product_id = p.product_id
    JOIN orders   o ON oi.order_id = o.order_id
    WHERE o.status = 'completed'
      AND p.brand IS NOT NULL
    GROUP BY p.category_id, p.brand
),
share_rate AS (
    SELECT category_id, brand, amt,
           amt / NULLIF(SUM(amt) OVER (PARTITION BY category_id), 0) AS share_rate
    FROM brand
)
SELECT c.category_name, s.brand, ROUND(s.amt, 2) AS amt,
       ROUND(s.share_rate * 100, 2) AS share_pct,
       ROW_NUMBER() OVER (PARTITION BY s.category_id ORDER BY s.amt DESC) AS brand_rank
FROM share_rate s
JOIN categories c ON s.category_id = c.category_id
ORDER BY c.category_name, brand_rank;

-- ------------------------------------------------------------------------------
-- [066] 毛利率·盈利分析 | 电商 | 商品毛利率分析与低毛利商品预警
-- ------------------------------------------------------------------------------
WITH g AS (
    SELECT p.product_id, p.product_name, c.category_name,
           SUM(oi.amount)                    AS revenue,
           SUM(oi.quantity * p.cost)         AS cost_amt,
           SUM(oi.quantity)                  AS qty
    FROM order_items oi
    JOIN products   p ON oi.product_id = p.product_id
    JOIN categories c ON p.category_id = c.category_id
    JOIN orders     o ON oi.order_id = o.order_id
    WHERE o.status = 'completed'
      AND o.order_date >= (TRUNC(SYSDATE) - 90)
    GROUP BY p.product_id, p.product_name, c.category_name
)
SELECT product_name, category_name,
       ROUND(revenue, 2) AS revenue,
       ROUND(cost_amt, 2) AS cost_amt,
       ROUND((revenue - cost_amt) / NULLIF(revenue, 0) * 100, 2) AS gross_margin_pct,
       qty,
       CASE WHEN (revenue - cost_amt) / NULLIF(revenue, 0) < 0.1 THEN 'low_margin'
            WHEN (revenue - cost_amt) / NULLIF(revenue, 0) > 0.5 THEN 'high_margin'
            ELSE 'normal' END AS margin_flag
FROM g
ORDER BY gross_margin_pct ASC
FETCH FIRST 200 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [067] 贡献·增长分解 | 电商 | 品类增长贡献分解（各品类对总增长拉动）
-- ------------------------------------------------------------------------------
WITH cm AS (
    SELECT p.category_id,
           SUM(CASE WHEN EXTRACT(YEAR FROM o.order_date) = EXTRACT(YEAR FROM TRUNC(SYSDATE))   THEN oi.amount ELSE 0 END) AS amt_cur,
           SUM(CASE WHEN EXTRACT(YEAR FROM o.order_date) = EXTRACT(YEAR FROM TRUNC(SYSDATE)) - 1 THEN oi.amount ELSE 0 END) AS amt_prev
    FROM order_items oi
    JOIN products p ON oi.product_id = p.product_id
    JOIN orders   o ON oi.order_id = o.order_id
    WHERE o.status = 'completed'
    GROUP BY p.category_id
)
SELECT c.category_name,
       ROUND(amt_prev, 2) AS amt_prev,
       ROUND(amt_cur, 2)  AS amt_cur,
       ROUND(amt_cur - amt_prev, 2) AS delta,
       ROUND((amt_cur - amt_prev) / NULLIF(amt_prev, 0) * 100, 2) AS growth_pct,
       ROUND((amt_cur - amt_prev) / NULLIF(SUM(amt_cur - amt_prev) OVER (), 0) * 100, 2) AS contribution_pct
FROM cm
JOIN categories c ON cm.category_id = c.category_id
ORDER BY delta DESC;

-- ------------------------------------------------------------------------------
-- [068] 预测·趋势外推 | 电商 | 基于近 3 月趋势的商品销量外推预测
-- ------------------------------------------------------------------------------
WITH m AS (
    SELECT oi.product_id, TO_CHAR(o.order_date, 'YYYY-MM') AS ym, SUM(oi.quantity) AS qty
    FROM order_items oi
    JOIN orders o ON oi.order_id = o.order_id
    WHERE o.status = 'completed'
      AND o.order_date >= (TRUNC(SYSDATE) - 120)
    GROUP BY oi.product_id, TO_CHAR(o.order_date, 'YYYY-MM')
),
piv AS (
    SELECT product_id,
           SUM(CASE WHEN ym = TO_CHAR((TRUNC(TRUNC(SYSDATE), 'MM') - INTERVAL '2' MONTH), 'YYYY-MM') THEN qty ELSE 0 END) AS q1,
           SUM(CASE WHEN ym = TO_CHAR((TRUNC(TRUNC(SYSDATE), 'MM') - INTERVAL '1' MONTH), 'YYYY-MM') THEN qty ELSE 0 END) AS q2,
           SUM(CASE WHEN ym = TO_CHAR(TRUNC(TRUNC(SYSDATE), 'MM'), 'YYYY-MM') THEN qty ELSE 0 END) AS q3
    FROM m GROUP BY product_id
)
SELECT p.product_name, pv.q1, pv.q2, pv.q3,
       ROUND((pv.q3 - pv.q2) / NULLIF(pv.q2, 0) * 100, 2) AS mom_pct,
       ROUND(pv.q3 + (pv.q3 - pv.q2), 0)                  AS forecast_next_month,
       CASE WHEN pv.q3 > pv.q2 AND pv.q2 > pv.q1 THEN 'rising'
            WHEN pv.q3 < pv.q2 AND pv.q2 < pv.q1 THEN 'falling'
            ELSE 'volatile' END AS trend
FROM piv pv
JOIN products p ON pv.product_id = p.product_id
WHERE pv.q2 > 0
ORDER BY forecast_next_month DESC
FETCH FIRST 150 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [069] 促销·毛利影响 | 电商 | 促销商品毛利侵蚀与净收益评估
-- ------------------------------------------------------------------------------
WITH pm_sales AS (
    SELECT oi.product_id,
           SUM(CASE WHEN o.discount_amount > 0 THEN oi.amount ELSE 0 END) AS promo_amt,
           SUM(CASE WHEN o.discount_amount > 0 THEN o.discount_amount ELSE 0 END) AS promo_discount,
           SUM(CASE WHEN o.discount_amount = 0 THEN oi.amount ELSE 0 END) AS normal_amt,
           SUM(oi.quantity * p.cost) AS cost_amt
    FROM order_items oi
    JOIN orders   o ON oi.order_id = o.order_id
    JOIN products p ON oi.product_id = p.product_id
    WHERE o.status = 'completed'
      AND o.order_date >= (TRUNC(SYSDATE) - 90)
    GROUP BY oi.product_id
)
SELECT p.product_name,
       ROUND(ps.promo_amt, 2)     AS promo_revenue,
       ROUND(ps.promo_discount, 2) AS promo_discount,
       ROUND(ps.normal_amt, 2)    AS normal_revenue,
       ROUND((ps.promo_amt - ps.promo_discount - ps.cost_amt)
             / NULLIF(ps.promo_amt, 0) * 100, 2) AS net_margin_pct,
       ROUND(ps.promo_discount / NULLIF(ps.promo_amt, 0) * 100, 2) AS discount_rate_pct
FROM pm_sales ps
JOIN products p ON ps.product_id = p.product_id
WHERE ps.promo_amt > 0
ORDER BY net_margin_pct ASC
FETCH FIRST 150 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [070] 优惠券·叠加 | 电商 | 多券叠加使用与订单优惠结构分析
-- ------------------------------------------------------------------------------
WITH order_coupon AS (
    SELECT order_id, COUNT(*) AS coupon_cnt, SUM(face_value) AS total_face
    FROM coupons
    WHERE status = 'used'
    GROUP BY order_id
)
SELECT oc.coupon_cnt,
       COUNT(*)                          AS order_cnt,
       ROUND(AVG(oc.total_face), 2)      AS avg_face_value,
       ROUND(AVG(o.pay_amount), 2)       AS avg_pay_amount,
       ROUND(AVG(o.discount_amount), 2)  AS avg_discount,
       ROUND(AVG(o.discount_amount) / NULLIF(AVG(o.pay_amount + o.discount_amount), 0) * 100, 2) AS discount_ratio_pct,
       ROUND(AVG(o.pay_amount), 2)       AS avg_net_pay
FROM order_coupon oc
JOIN orders o ON oc.order_id = o.order_id
WHERE o.status = 'completed'
GROUP BY oc.coupon_cnt
ORDER BY oc.coupon_cnt;

-- ------------------------------------------------------------------------------
-- [071] 大促·活动复盘 | 电商 | 大促活动期间销售爆发与前后对比
-- ------------------------------------------------------------------------------
WITH daily AS (
    SELECT CAST(order_date AS DATE) AS dt,
           SUM(pay_amount) AS gmv,
           COUNT(*)        AS order_cnt,
           COUNT(DISTINCT customer_id) AS uv
    FROM orders
    WHERE status = 'completed'
    GROUP BY CAST(order_date AS DATE)
),
promo_days AS (
    SELECT DISTINCT CAST((d.dt + 0) AS DATE) AS dt
    FROM daily d
    JOIN promotions pm ON d.dt BETWEEN pm.start_date AND pm.end_date
)
SELECT CASE WHEN pd.dt IS NOT NULL THEN 'promo_day' ELSE 'normal_day' END AS day_type,
       COUNT(*)                          AS day_cnt,
       ROUND(AVG(dl.gmv), 2)             AS avg_gmv,
       ROUND(AVG(dl.order_cnt), 1)       AS avg_orders,
       ROUND(AVG(dl.uv), 1)              AS avg_uv,
       ROUND(AVG(dl.gmv) / NULLIF(AVG(dl.uv), 0), 2) AS avg_gmv_per_uv,
       MAX(dl.gmv)                       AS peak_gmv
FROM daily dl
LEFT JOIN promo_days pd ON dl.dt = pd.dt
GROUP BY CASE WHEN pd.dt IS NOT NULL THEN 'promo_day' ELSE 'normal_day' END;

-- ------------------------------------------------------------------------------
-- [072] 渠道·获客质量 | 电商 | 渠道新客获取质量（首单金额与留存）
-- ------------------------------------------------------------------------------
WITH first_ord AS (
    SELECT customer_id, channel,
           MIN(order_date)  AS first_dt,
           SUM(pay_amount)  AS first_amt
    FROM orders
    WHERE status = 'completed'
    GROUP BY customer_id, channel
),
fo AS (
    SELECT customer_id, channel, first_amt,
           ROW_NUMBER() OVER (PARTITION BY customer_id ORDER BY first_dt) AS rn
    FROM first_ord
),
retention AS (
    SELECT f.customer_id, f.channel, f.first_amt,
           COUNT(o.order_id) AS later_orders
    FROM fo f
    LEFT JOIN orders o ON f.customer_id = o.customer_id
         AND o.order_date > (TRUNC(SYSDATE) - 0)
    WHERE f.rn = 1
    GROUP BY f.customer_id, f.channel, f.first_amt
)
SELECT channel,
       COUNT(*)                                        AS new_cust_cnt,
       ROUND(AVG(first_amt), 2)                        AS avg_first_amt,
       SUM(CASE WHEN later_orders > 1 THEN 1 ELSE 0 END) AS retained_cnt,
       ROUND(SUM(CASE WHEN later_orders > 1 THEN 1 ELSE 0 END)
             / NULLIF(COUNT(*), 0) * 100, 2)           AS retention_pct
FROM retention
GROUP BY channel
ORDER BY new_cust_cnt DESC;

-- ------------------------------------------------------------------------------
-- [073] 投入产出·营销 | 电商 | 营销活动投入产出比（ROI）综合评估
-- ------------------------------------------------------------------------------
WITH pm AS (
    SELECT promo_id, promo_name, budget, category_id, start_date, end_date
    FROM promotions
),
effect AS (
    SELECT pm.promo_id,
           COUNT(DISTINCT o.order_id)   AS order_cnt,
           COUNT(DISTINCT o.customer_id) AS cust_cnt,
           SUM(o.pay_amount)            AS gmv,
           SUM(o.discount_amount)       AS discount
    FROM pm
    LEFT JOIN orders o ON o.order_date BETWEEN pm.start_date AND pm.end_date
         AND o.status = 'completed'
    GROUP BY pm.promo_id
)
SELECT pm.promo_name, pm.budget,
       COALESCE(e.order_cnt, 0) AS order_cnt,
       COALESCE(e.cust_cnt, 0)  AS cust_cnt,
       ROUND(COALESCE(e.gmv, 0), 2)      AS gmv,
       ROUND(COALESCE(e.discount, 0), 2) AS discount,
       ROUND(COALESCE(e.gmv, 0) / NULLIF(pm.budget + COALESCE(e.discount, 0), 0), 2) AS roi,
       ROUND((pm.budget + COALESCE(e.discount, 0)) / NULLIF(COALESCE(e.cust_cnt, 0), 0), 2) AS cost_per_cust
FROM pm
LEFT JOIN effect e ON pm.promo_id = e.promo_id
ORDER BY roi DESC;

-- ------------------------------------------------------------------------------
-- [074] 分层·营销名单 | 电商 | 高价值流失风险用户精准营销名单
-- ------------------------------------------------------------------------------
WITH rfm AS (
    SELECT o.customer_id,
           (TRUNC(SYSDATE) - MAX(o.order_date)) AS recency,
           COUNT(*)                                 AS frequency,
           SUM(o.pay_amount)                        AS monetary
    FROM orders o
    WHERE o.status = 'completed'
    GROUP BY o.customer_id
),
seg AS (
    SELECT customer_id, recency, frequency, monetary,
           NTILE(5) OVER (ORDER BY recency DESC) AS r,
           NTILE(5) OVER (ORDER BY frequency)    AS f,
           NTILE(5) OVER (ORDER BY monetary)     AS m
    FROM rfm
)
SELECT c.customer_id, c.customer_name, c."LEVEL", c.city,
       s.recency, s.frequency, ROUND(s.monetary, 2) AS monetary,
       CASE WHEN s.r <= 2 AND s.m >= 4 THEN 'high_value_at_risk'
            WHEN s.r <= 2 AND s.f >= 4 THEN 'loyal_at_risk'
            WHEN s.r >= 4 AND s.m >= 4 THEN 'new_high_value'
            ELSE 'other' END AS marketing_segment
FROM seg s
JOIN customers c ON s.customer_id = c.customer_id
WHERE (s.r <= 2 AND s.m >= 4) OR (s.r <= 2 AND s.f >= 4) OR (s.r >= 4 AND s.m >= 4)
ORDER BY s.monetary DESC
FETCH FIRST 500 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [075] 多仓·调拨建议 | 电商 | 多仓库库存分布与调拨建议
-- ------------------------------------------------------------------------------
WITH wh AS (
    SELECT i.product_id, i.warehouse_id, i.quantity, i.safety_stock,
           w.city
    FROM inventory i
    JOIN warehouses w ON i.warehouse_id = w.warehouse_id
),
demand AS (
    SELECT oi.product_id, SUM(oi.quantity) AS qty_30d
    FROM order_items oi
    JOIN orders o ON oi.order_id = o.order_id
    WHERE o.status = 'completed'
      AND o.order_date >= (TRUNC(SYSDATE) - 30)
    GROUP BY oi.product_id
),
agg AS (
    SELECT product_id,
           SUM(quantity)     AS total_stock,
           SUM(safety_stock) AS total_safety,
           COUNT(*)          AS wh_cnt,
           MIN(quantity)     AS min_wh_qty,
           MAX(quantity)     AS max_wh_qty
    FROM wh GROUP BY product_id
)
SELECT p.product_name, a.total_stock, a.total_safety, a.wh_cnt,
       COALESCE(d.qty_30d, 0) AS demand_30d,
       ROUND(a.total_stock / NULLIF(a.wh_cnt, 0), 1) AS avg_wh_stock,
       CASE WHEN a.total_stock < a.total_safety THEN 'need_replenish'
            WHEN a.min_wh_qty = 0 AND a.max_wh_qty > 100 THEN 'need_transfer'
            ELSE 'balanced' END AS action
FROM agg a
LEFT JOIN demand d ON a.product_id = d.product_id
JOIN products p    ON a.product_id = p.product_id
WHERE a.total_stock < a.total_safety OR (a.min_wh_qty = 0 AND a.max_wh_qty > 100)
ORDER BY a.total_stock ASC
FETCH FIRST 200 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [076] 周转·仓库健康 | 电商 | 仓库库存周转天数与库存健康度评估
-- ------------------------------------------------------------------------------
WITH wh_stock AS (
    SELECT i.warehouse_id,
           SUM(i.quantity)             AS stock_qty,
           SUM(i.safety_stock)         AS safety_qty,
           COUNT(DISTINCT i.product_id) AS sku_cnt
    FROM inventory i
    GROUP BY i.warehouse_id
),
wh_sales AS (
    SELECT i.warehouse_id,
           SUM(oi.quantity) AS qty_sold,
           SUM(oi.amount)   AS amt_sold
    FROM order_items oi
    JOIN orders    o ON oi.order_id = o.order_id
    JOIN inventory i ON oi.product_id = i.product_id
    WHERE o.status = 'completed'
      AND o.order_date >= (TRUNC(SYSDATE) - 90)
    GROUP BY i.warehouse_id
)
SELECT w.warehouse_name, w.city,
       ws.sku_cnt,
       ws.stock_qty,
       COALESCE(sl.qty_sold, 0) AS qty_sold_90d,
       ROUND(ws.stock_qty / NULLIF(COALESCE(sl.qty_sold, 0) / 90.0, 0), 1) AS turnover_days,
       ROUND(COALESCE(sl.amt_sold, 0), 2) AS amt_sold_90d,
       CASE WHEN ws.stock_qty <= ws.safety_qty THEN 'under_safety'
            WHEN ws.stock_qty / NULLIF(COALESCE(sl.qty_sold, 0) / 90.0, 0) > 120 THEN 'overstock'
            ELSE 'healthy' END AS health_status,
       SUM(ws.stock_qty) OVER () AS total_stock_all
FROM warehouses w
JOIN wh_stock ws ON w.warehouse_id = ws.warehouse_id
LEFT JOIN wh_sales sl ON w.warehouse_id = sl.warehouse_id
ORDER BY turnover_days DESC;

-- ------------------------------------------------------------------------------
-- [077] 对比·承运商 | 电商 | 承运商时效与异常率对比评估
-- ------------------------------------------------------------------------------
SELECT s.carrier,
       COUNT(*)                                          AS shipment_cnt,
       ROUND(AVG(s.delivery_days), 2)                    AS avg_days,
       ROUND(MAX(s.delivery_days), 2)                    AS max_days,
       SUM(CASE WHEN s.status = 'exception' THEN 1 ELSE 0 END) AS exception_cnt,
       ROUND(SUM(CASE WHEN s.status = 'exception' THEN 1 ELSE 0 END)
             / NULLIF(COUNT(*), 0) * 100, 2)             AS exception_pct,
       SUM(CASE WHEN s.delivery_days <= 3 THEN 1 ELSE 0 END) AS ontime_cnt,
       ROUND(SUM(CASE WHEN s.delivery_days <= 3 THEN 1 ELSE 0 END)
             / NULLIF(COUNT(*), 0) * 100, 2)             AS ontime_pct,
       RANK() OVER (ORDER BY SUM(CASE WHEN s.delivery_days <= 3 THEN 1 ELSE 0 END)
             / NULLIF(COUNT(*), 0) DESC)                 AS carrier_rank
FROM shipments s
WHERE s.ship_date >= (TRUNC(SYSDATE) - 180)
GROUP BY s.carrier
ORDER BY ontime_pct DESC;

-- ------------------------------------------------------------------------------
-- [078] 逆向·退货物流 | 电商 | 退货逆向物流时效与成本分析
-- ------------------------------------------------------------------------------
WITH r AS (
    SELECT r.refund_id, r.order_id, r.refund_date, r.refund_amount, r.reason,
           o.order_date, o.customer_id
    FROM refunds r
    JOIN orders o ON r.order_id = o.order_id
    WHERE r.refund_date >= (TRUNC(SYSDATE) - 180)
)
SELECT reason,
       COUNT(*)                                            AS refund_cnt,
       ROUND(SUM(refund_amount), 2)                        AS refund_amt,
       ROUND(AVG(refund_amount), 2)                        AS avg_refund,
       ROUND(AVG((refund_date - order_date)), 1)  AS avg_refund_lag_days,
       MAX((refund_date - order_date))            AS max_lag_days,
       ROUND(SUM(refund_amount) / NULLIF(SUM(SUM(refund_amount)) OVER (), 0) * 100, 2) AS amt_share_pct
FROM r
GROUP BY reason
ORDER BY refund_amt DESC;

-- ------------------------------------------------------------------------------
-- [079] 区域·签收异常 | 电商 | 区域签收异常率与高发省份识别
-- ------------------------------------------------------------------------------
SELECT s.province,
       COUNT(*)                                                AS total_cnt,
       SUM(CASE WHEN s.status = 'exception' THEN 1 ELSE 0 END) AS exception_cnt,
       ROUND(SUM(CASE WHEN s.status = 'exception' THEN 1 ELSE 0 END)
             / NULLIF(COUNT(*), 0) * 100, 2)                   AS exception_pct,
       ROUND(AVG(s.delivery_days), 2)                          AS avg_days,
       COUNT(DISTINCT s.carrier)                               AS carrier_cnt,
       RANK() OVER (ORDER BY SUM(CASE WHEN s.status = 'exception' THEN 1 ELSE 0 END)
             / NULLIF(COUNT(*), 0) DESC)                       AS risk_rank
FROM shipments s
WHERE s.ship_date >= (TRUNC(SYSDATE) - 180)
GROUP BY s.province
HAVING COUNT(*) >= 50
ORDER BY exception_pct DESC;

-- ------------------------------------------------------------------------------
-- [080] 拆单分析 | 电商 | 订单拆单率与拆单原因分析
-- ------------------------------------------------------------------------------
WITH oi AS (
    SELECT o.order_id, o.pay_amount,
           COUNT(DISTINCT oi.product_id) AS sku_cnt,
           COUNT(DISTINCT p.category_id) AS cat_cnt
    FROM orders o
    JOIN order_items oi ON o.order_id = oi.order_id
    JOIN products    p  ON oi.product_id = p.product_id
    WHERE o.status = 'completed'
    GROUP BY o.order_id, o.pay_amount
),
shp AS (
    SELECT order_id, COUNT(*) AS shipment_cnt
    FROM shipments GROUP BY order_id
)
SELECT CASE WHEN COALESCE(sh.shipment_cnt, 0) > 1 THEN 'split' ELSE 'single' END AS order_type,
       COUNT(*)                      AS order_cnt,
       ROUND(AVG(oi.pay_amount), 2)  AS avg_amount,
       ROUND(AVG(oi.sku_cnt), 2)     AS avg_sku,
       ROUND(AVG(oi.cat_cnt), 2)     AS avg_cat
FROM oi
LEFT JOIN shp sh ON oi.order_id = sh.order_id
GROUP BY CASE WHEN COALESCE(sh.shipment_cnt, 0) > 1 THEN 'split' ELSE 'single' END;

-- ------------------------------------------------------------------------------
-- [081] 支付·方式偏好 | 电商 | 支付方式偏好、成功率与金额分布
-- ------------------------------------------------------------------------------
SELECT p.pay_method,
       COUNT(*)                    AS pay_cnt,
       COUNT(DISTINCT p.order_id)  AS order_cnt,
       ROUND(SUM(p.pay_amount), 2) AS total_amt,
       ROUND(AVG(p.pay_amount), 2) AS avg_amt,
       ROUND(SUM(p.pay_amount) / NULLIF(SUM(SUM(p.pay_amount)) OVER (), 0) * 100, 2) AS amt_share_pct,
       SUM(CASE WHEN p.status = 'success' THEN 1 ELSE 0 END) AS success_cnt,
       ROUND(SUM(CASE WHEN p.status = 'success' THEN 1 ELSE 0 END)
             / NULLIF(COUNT(*), 0) * 100, 2) AS success_rate_pct,
       SUM(CASE WHEN p.status = 'failed'  THEN 1 ELSE 0 END) AS failed_cnt
FROM payments p
WHERE p.pay_date >= (TRUNC(SYSDATE) - 90)
GROUP BY p.pay_method
ORDER BY total_amt DESC;

-- ------------------------------------------------------------------------------
-- [082] 异常·支付监控 | 电商 | 支付失败率日趋势与突增告警
-- ------------------------------------------------------------------------------
WITH dp AS (
    SELECT CAST(pay_date AS DATE) AS dt,
           COUNT(*)                                                  AS total_cnt,
           SUM(CASE WHEN status <> 'success' THEN 1 ELSE 0 END)      AS fail_cnt,
           SUM(CASE WHEN status <> 'success' THEN pay_amount ELSE 0 END) AS fail_amt
    FROM payments
    WHERE pay_date >= (TRUNC(SYSDATE) - 60)
    GROUP BY CAST(pay_date AS DATE)
),
ma AS (
    SELECT dt, total_cnt, fail_cnt, fail_amt,
           ROUND(fail_cnt / NULLIF(total_cnt, 0) * 100, 2) AS fail_rate,
           ROUND(AVG(fail_cnt / NULLIF(total_cnt, 0) * 100)
                 OVER (ORDER BY dt ROWS BETWEEN 7 PRECEDING AND 1 PRECEDING), 2) AS base_rate
    FROM dp
)
SELECT dt, total_cnt, fail_cnt, ROUND(fail_amt, 2) AS fail_amt, fail_rate, base_rate,
       ROUND(fail_rate - base_rate, 2) AS delta_rate,
       CASE WHEN fail_rate > base_rate * 2 AND fail_rate > 5 THEN 'alert'
            WHEN fail_rate > base_rate * 1.5 THEN 'watch'
            ELSE 'normal' END AS alert_level
FROM ma
ORDER BY dt DESC;

-- ------------------------------------------------------------------------------
-- [083] 风控·大额审核 | 电商 | 大额订单风险分级与人工审核名单
-- ------------------------------------------------------------------------------
WITH cust_hist AS (
    SELECT customer_id,
           AVG(pay_amount) AS avg_amt,
           MAX(pay_amount) AS max_amt,
           COUNT(*)        AS order_cnt,
           MIN(order_date) AS first_order
    FROM orders
    WHERE status = 'completed'
    GROUP BY customer_id
)
SELECT o.order_id, o.customer_id, ROUND(o.pay_amount, 2) AS pay_amount, o.order_date,
       ROUND(h.avg_amt, 2)                       AS hist_avg_amt,
       h.order_cnt                               AS hist_order_cnt,
       ROUND(o.pay_amount / NULLIF(h.avg_amt, 0), 2) AS multiple_of_avg,
       (o.order_date - h.first_order)   AS days_since_first,
       CASE WHEN o.pay_amount >= 50000 THEN 'manual_review'
            WHEN o.pay_amount >= 10000 AND h.order_cnt <= 2 THEN 'new_cust_review'
            WHEN o.pay_amount >= 10000 AND o.pay_amount > h.max_amt * 3 THEN 'abnormal_review'
            ELSE 'auto_pass' END AS review_level
FROM orders o
JOIN cust_hist h ON o.customer_id = h.customer_id
WHERE o.pay_amount >= 10000
  AND o.order_date >= (TRUNC(SYSDATE) - 30)
ORDER BY o.pay_amount DESC
FETCH FIRST 300 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [084] 状态·订单健康 | 电商 | 订单状态分布与异常状态占比趋势
-- ------------------------------------------------------------------------------
SELECT o.status,
       COUNT(*)                    AS order_cnt,
       ROUND(SUM(o.pay_amount), 2) AS total_amt,
       ROUND(AVG(o.pay_amount), 2) AS avg_amt,
       ROUND(COUNT(*) / NULLIF(SUM(COUNT(*)) OVER (), 0) * 100, 2) AS cnt_share_pct,
       COUNT(DISTINCT o.customer_id) AS cust_cnt,
       COUNT(DISTINCT o.store_id)    AS store_cnt,
       MIN(o.order_date) AS earliest,
       MAX(o.order_date) AS latest
FROM orders o
WHERE o.order_date >= (TRUNC(SYSDATE) - 90)
GROUP BY o.status
ORDER BY order_cnt DESC;

-- ------------------------------------------------------------------------------
-- [085] 库存·缺货预警 | 电商 | 低库存商品占比与缺货风险品类分布
-- ------------------------------------------------------------------------------
WITH st AS (
    SELECT p.category_id,
           COUNT(*)                                                    AS sku_cnt,
           SUM(CASE WHEN i.quantity <= 0 THEN 1 ELSE 0 END)            AS out_of_stock,
           SUM(CASE WHEN i.quantity > 0 AND i.quantity <= i.safety_stock THEN 1 ELSE 0 END) AS below_safety,
           SUM(i.quantity)                                             AS total_qty
    FROM products p
    JOIN inventory i ON p.product_id = i.product_id
    WHERE p.status = 'on_sale'
    GROUP BY p.category_id
)
SELECT c.category_name,
       s.sku_cnt, s.out_of_stock, s.below_safety, s.total_qty,
       ROUND(s.out_of_stock / NULLIF(s.sku_cnt, 0) * 100, 2)  AS oos_pct,
       ROUND((s.out_of_stock + s.below_safety) / NULLIF(s.sku_cnt, 0) * 100, 2) AS risk_pct,
       RANK() OVER (ORDER BY (s.out_of_stock + s.below_safety)
             / NULLIF(s.sku_cnt, 0) DESC) AS risk_rank
FROM st s
JOIN categories c ON s.category_id = c.category_id
ORDER BY risk_pct DESC;

-- ------------------------------------------------------------------------------
-- [086] 季节性·销售指数 | 电商 | 月度季节性指数（季节因素对销售影响）
-- ------------------------------------------------------------------------------
WITH m AS (
    SELECT EXTRACT(MONTH FROM order_date) AS mon,
           EXTRACT(YEAR FROM order_date)  AS yr,
           SUM(pay_amount) AS gmv
    FROM orders
    WHERE status = 'completed'
    GROUP BY EXTRACT(MONTH FROM order_date), EXTRACT(YEAR FROM order_date)
),
idx AS (
    SELECT mon, yr, gmv,
           AVG(gmv) OVER (PARTITION BY yr)                     AS year_avg,
           AVG(gmv) OVER (PARTITION BY mon)                    AS month_avg_all_year,
           AVG(gmv) OVER ()                                    AS grand_avg
    FROM m
)
SELECT mon,
       COUNT(*)                                   AS year_cnt,
       ROUND(AVG(gmv), 2)                         AS avg_gmv,
       ROUND(AVG(month_avg_all_year), 2)          AS month_avg,
       ROUND(AVG(month_avg_all_year) / NULLIF(AVG(grand_avg), 0) * 100, 2) AS seasonal_index,
       CASE WHEN AVG(month_avg_all_year) / NULLIF(AVG(grand_avg), 0) >= 1.2 THEN 'peak'
            WHEN AVG(month_avg_all_year) / NULLIF(AVG(grand_avg), 0) <= 0.8 THEN 'trough'
            ELSE 'normal' END AS season_type
FROM idx
GROUP BY mon
ORDER BY mon;

-- ------------------------------------------------------------------------------
-- [087] 波动·峰值识别 | 电商 | 销售峰值日识别与波动率分析
-- ------------------------------------------------------------------------------
WITH d AS (
    SELECT CAST(order_date AS DATE) AS dt, SUM(pay_amount) AS gmv
    FROM orders
    WHERE status = 'completed'
      AND order_date >= (TRUNC(SYSDATE) - 365)
    GROUP BY CAST(order_date AS DATE)
),
stat AS (
    SELECT dt, gmv,
           AVG(gmv) OVER (ORDER BY dt ROWS BETWEEN 30 PRECEDING AND 1 PRECEDING) AS base30,
           AVG(gmv) OVER () AS grand_avg
    FROM d
)
SELECT dt, ROUND(gmv, 2) AS gmv, ROUND(base30, 2) AS base30,
       ROUND(gmv / NULLIF(base30, 0), 2) AS surge_multiple,
       ROUND((gmv - base30) / NULLIF(base30, 0) * 100, 2) AS surge_pct,
       CASE WHEN gmv / NULLIF(base30, 0) >= 3 THEN 'mega_peak'
            WHEN gmv / NULLIF(base30, 0) >= 2 THEN 'peak'
            ELSE 'normal' END AS peak_type
FROM stat
WHERE base30 IS NOT NULL
ORDER BY surge_multiple DESC
FETCH FIRST 50 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [088] 对比·周末效应 | 电商 | 工作日与周末销售/流量差异对比
-- ------------------------------------------------------------------------------
SELECT CASE WHEN (TO_NUMBER(TO_CHAR(o.order_date, 'D')) - 1) IN (0, 6) THEN 'weekend' ELSE 'weekday' END AS day_type,
       (TO_NUMBER(TO_CHAR(o.order_date, 'D')) - 1) AS dow,
       COUNT(*)                    AS order_cnt,
       COUNT(DISTINCT o.customer_id) AS uv,
       ROUND(SUM(o.pay_amount), 2) AS gmv,
       ROUND(AVG(o.pay_amount), 2) AS avg_order_amt,
       ROUND(SUM(o.pay_amount) / NULLIF(COUNT(DISTINCT o.customer_id), 0), 2) AS gmv_per_uv,
       ROUND(SUM(o.discount_amount) / NULLIF(SUM(o.pay_amount + o.discount_amount), 0) * 100, 2) AS discount_pct
FROM orders o
WHERE o.status = 'completed'
  AND o.order_date >= (TRUNC(SYSDATE) - 90)
GROUP BY CASE WHEN (TO_NUMBER(TO_CHAR(o.order_date, 'D')) - 1) IN (0, 6) THEN 'weekend' ELSE 'weekday' END,
         (TO_NUMBER(TO_CHAR(o.order_date, 'D')) - 1)
ORDER BY dow;

-- ------------------------------------------------------------------------------
-- [089] 排名·城市消费力 | 电商 | 城市消费力排名与人均消费分层
-- ------------------------------------------------------------------------------
WITH city AS (
    SELECT c.city, c.province,
           COUNT(DISTINCT c.customer_id) AS reg_cust,
           COUNT(DISTINCT o.customer_id) AS buy_cust,
           SUM(o.pay_amount)             AS gmv,
           COUNT(o.order_id)             AS order_cnt
    FROM customers c
    LEFT JOIN orders o ON c.customer_id = o.customer_id AND o.status = 'completed'
    GROUP BY c.city, c.province
)
SELECT city, province, reg_cust, buy_cust, order_cnt,
       ROUND(gmv, 2)                                     AS gmv,
       ROUND(gmv / NULLIF(buy_cust, 0), 2)               AS gmv_per_buyer,
       ROUND(buy_cust / NULLIF(reg_cust, 0) * 100, 2)    AS activation_pct,
       ROUND(gmv / NULLIF(SUM(gmv) OVER (), 0) * 100, 2) AS gmv_share_pct,
       RANK() OVER (ORDER BY gmv DESC)                   AS city_rank
FROM city
WHERE reg_cust >= 50
ORDER BY gmv DESC
FETCH FIRST 100 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [090] 渗透·市场分析 | 电商 | 区域市场渗透率与增长潜力评估
-- ------------------------------------------------------------------------------
WITH area AS (
    SELECT c.province,
           COUNT(DISTINCT c.customer_id) AS reg_cust,
           COUNT(DISTINCT CASE WHEN o.order_id IS NOT NULL THEN c.customer_id END) AS buy_cust,
           SUM(o.pay_amount) AS gmv
    FROM customers c
    LEFT JOIN orders o ON c.customer_id = o.customer_id AND o.status = 'completed'
    GROUP BY c.province
),
recent AS (
    SELECT c.province, SUM(o.pay_amount) AS gmv_90d
    FROM customers c
    JOIN orders o ON c.customer_id = o.customer_id
    WHERE o.status = 'completed' AND o.order_date >= (TRUNC(SYSDATE) - 90)
    GROUP BY c.province
)
SELECT a.province, a.reg_cust, a.buy_cust,
       ROUND(a.buy_cust / NULLIF(a.reg_cust, 0) * 100, 2) AS penetration_pct,
       ROUND(a.gmv, 2) AS total_gmv,
       ROUND(COALESCE(r.gmv_90d, 0), 2) AS gmv_90d,
       ROUND(COALESCE(r.gmv_90d, 0) / NULLIF(a.gmv, 0) * 100, 2) AS recent_activity_pct,
       CASE WHEN a.buy_cust / NULLIF(a.reg_cust, 0) < 0.3 THEN 'high_potential'
            WHEN a.buy_cust / NULLIF(a.reg_cust, 0) > 0.7 THEN 'mature'
            ELSE 'growing' END AS market_stage
FROM area a
LEFT JOIN recent r ON a.province = r.province
ORDER BY penetration_pct ASC;

-- ------------------------------------------------------------------------------
-- [091] 复购·门店维度 | 电商 | 门店客户复购率与忠诚度对比
-- ------------------------------------------------------------------------------
WITH so AS (
    SELECT s.store_id, s.store_name, s.region,
           o.customer_id, COUNT(*) AS order_cnt, SUM(o.pay_amount) AS amt
    FROM stores s
    JOIN orders o ON s.store_id = o.store_id
    WHERE o.status = 'completed'
      AND o.order_date >= (TRUNC(SYSDATE) - 180)
    GROUP BY s.store_id, s.store_name, s.region, o.customer_id
)
SELECT store_name, region,
       COUNT(*)                                                  AS cust_cnt,
       SUM(CASE WHEN order_cnt > 1 THEN 1 ELSE 0 END)            AS repeat_cust,
       ROUND(SUM(CASE WHEN order_cnt > 1 THEN 1 ELSE 0 END)
             / NULLIF(COUNT(*), 0) * 100, 2)                     AS repeat_rate_pct,
       ROUND(AVG(order_cnt), 2)                                  AS avg_orders_per_cust,
       ROUND(SUM(amt) / NULLIF(COUNT(*), 0), 2)                  AS arpu,
       RANK() OVER (ORDER BY SUM(CASE WHEN order_cnt > 1 THEN 1 ELSE 0 END)
             / NULLIF(COUNT(*), 0) DESC)                         AS loyalty_rank
FROM so
GROUP BY store_name, region
ORDER BY repeat_rate_pct DESC;

-- ------------------------------------------------------------------------------
-- [092] 结构·门店品类 | 电商 | 门店品类销售结构差异与特色品类识别
-- ------------------------------------------------------------------------------
WITH sc AS (
    SELECT s.store_id, s.store_name, p.category_id, SUM(oi.amount) AS amt
    FROM orders o
    JOIN stores      s  ON o.store_id = s.store_id
    JOIN order_items oi ON o.order_id = oi.order_id
    JOIN products    p  ON oi.product_id = p.product_id
    WHERE o.status = 'completed'
      AND o.order_date >= (TRUNC(SYSDATE) - 180)
    GROUP BY s.store_id, s.store_name, p.category_id
),
share_rate AS (
    SELECT store_name, category_id, amt,
           amt / NULLIF(SUM(amt) OVER (PARTITION BY store_name), 0) AS share_rate,
           ROW_NUMBER() OVER (PARTITION BY store_name ORDER BY amt DESC) AS cat_rank
    FROM sc
)
SELECT s.store_name, c.category_name, ROUND(s.amt, 2) AS amt,
       ROUND(s.share_rate * 100, 2) AS share_pct, s.cat_rank,
       ROUND(s.share_rate - AVG(s.share_rate) OVER (PARTITION BY s.category_id), 4) AS vs_category_avg
FROM share_rate s
JOIN categories c ON s.category_id = c.category_id
WHERE s.cat_rank <= 5
ORDER BY s.store_name, s.cat_rank;

-- ------------------------------------------------------------------------------
-- [093] 产出·门店效能 | 电商 | 门店单店产出与销售集中度分析
-- ------------------------------------------------------------------------------
WITH ss AS (
    SELECT s.store_id, s.store_name, s.region, s.city,
           COUNT(DISTINCT o.order_id)   AS order_cnt,
           COUNT(DISTINCT o.customer_id) AS cust_cnt,
           SUM(o.pay_amount)            AS gmv
    FROM stores s
    LEFT JOIN orders o ON s.store_id = o.store_id AND o.status = 'completed'
    GROUP BY s.store_id, s.store_name, s.region, s.city
)
SELECT store_name, city, region, order_cnt, cust_cnt, ROUND(gmv, 2) AS gmv,
       ROUND(gmv / NULLIF(order_cnt, 0), 2) AS avg_ticket,
       ROUND(gmv / NULLIF(cust_cnt, 0), 2)  AS avg_cust_value,
       ROUND(gmv / NULLIF(SUM(gmv) OVER (PARTITION BY region), 0) * 100, 2) AS region_share_pct,
       CUME_DIST() OVER (ORDER BY gmv)      AS gmv_cume_dist,
       PERCENT_RANK() OVER (ORDER BY gmv)   AS gmv_pct_rank
FROM ss
ORDER BY gmv DESC;

-- ------------------------------------------------------------------------------
-- [094] 预警·门店关停 | 电商 | 门店经营衰退预警（双降门店识别）
-- ------------------------------------------------------------------------------
WITH pm AS (
    SELECT s.store_id, s.store_name, s.region,
           SUM(CASE WHEN o.order_date >= (TRUNC(SYSDATE) - 90)  THEN o.pay_amount ELSE 0 END) AS gmv_90d,
           SUM(CASE WHEN o.order_date >= (TRUNC(SYSDATE) - 180)
                     AND o.order_date <  (TRUNC(SYSDATE) - 90)  THEN o.pay_amount ELSE 0 END) AS gmv_prev_90d,
           COUNT(CASE WHEN o.order_date >= (TRUNC(SYSDATE) - 90) THEN 1 END) AS orders_90d,
           COUNT(CASE WHEN o.order_date >= (TRUNC(SYSDATE) - 180)
                       AND o.order_date <  (TRUNC(SYSDATE) - 90) THEN 1 END) AS orders_prev_90d
    FROM stores s
    LEFT JOIN orders o ON s.store_id = o.store_id AND o.status = 'completed'
    GROUP BY s.store_id, s.store_name, s.region
)
SELECT store_name, region,
       ROUND(gmv_90d, 2)      AS gmv_90d,
       ROUND(gmv_prev_90d, 2) AS gmv_prev_90d,
       ROUND((gmv_90d - gmv_prev_90d) / NULLIF(gmv_prev_90d, 0) * 100, 2) AS gmv_change_pct,
       orders_90d, orders_prev_90d,
       ROUND((orders_90d - orders_prev_90d) / NULLIF(orders_prev_90d, 0) * 100, 2) AS order_change_pct,
       CASE WHEN gmv_90d = 0 AND gmv_prev_90d = 0 THEN 'dormant'
            WHEN (gmv_90d - gmv_prev_90d) / NULLIF(gmv_prev_90d, 0) < -0.3
                 AND (orders_90d - orders_prev_90d) / NULLIF(orders_prev_90d, 0) < -0.3 THEN 'shutdown_risk'
            WHEN (gmv_90d - gmv_prev_90d) / NULLIF(gmv_prev_90d, 0) < -0.1 THEN 'declining'
            ELSE 'stable' END AS store_status
FROM pm
ORDER BY gmv_change_pct ASC;

-- ------------------------------------------------------------------------------
-- [095] 迁移·价值变化 | 电商 | 用户价值分层迁移（近 3 月 vs 前 3 月）
-- ------------------------------------------------------------------------------
WITH amt AS (
    SELECT customer_id,
           SUM(CASE WHEN order_date >= (TRUNC(SYSDATE) - 90)  THEN pay_amount ELSE 0 END) AS amt_recent,
           SUM(CASE WHEN order_date >= (TRUNC(SYSDATE) - 180)
                     AND order_date <  (TRUNC(SYSDATE) - 90)  THEN pay_amount ELSE 0 END) AS amt_prev
    FROM orders
    WHERE status = 'completed'
    GROUP BY customer_id
),
tier AS (
    SELECT customer_id, amt_recent, amt_prev,
           NTILE(4) OVER (ORDER BY amt_recent) AS tier_recent,
           NTILE(4) OVER (ORDER BY amt_prev)   AS tier_prev
    FROM amt
)
SELECT tier_prev AS from_tier, tier_recent AS to_tier,
       COUNT(*)                        AS cust_cnt,
       ROUND(AVG(amt_prev), 2)         AS avg_amt_prev,
       ROUND(AVG(amt_recent), 2)       AS avg_amt_recent,
       ROUND(AVG(amt_recent - amt_prev), 2) AS avg_delta,
       CASE WHEN tier_recent > tier_prev THEN 'upgraded'
            WHEN tier_recent < tier_prev THEN 'downgraded'
            ELSE 'stable' END AS migration
FROM tier
GROUP BY tier_prev, tier_recent,
         CASE WHEN tier_recent > tier_prev THEN 'upgraded'
              WHEN tier_recent < tier_prev THEN 'downgraded'
              ELSE 'stable' END
ORDER BY from_tier, to_tier;

-- ------------------------------------------------------------------------------
-- [096] 分层·价值识别 | 电商 | 高频低价值用户识别与提升潜力评估
-- ------------------------------------------------------------------------------
WITH cust AS (
    SELECT o.customer_id,
           COUNT(*)          AS order_cnt,
           SUM(o.pay_amount) AS total_amt,
           AVG(o.pay_amount) AS avg_amt,
           SUM(o.discount_amount) AS total_discount
    FROM orders o
    WHERE o.status = 'completed'
      AND o.order_date >= (TRUNC(SYSDATE) - 180)
    GROUP BY o.customer_id
),
scored AS (
    SELECT customer_id, order_cnt, total_amt, avg_amt, total_discount,
           NTILE(5) OVER (ORDER BY order_cnt) AS freq_tier,
           NTILE(5) OVER (ORDER BY avg_amt)   AS value_tier
    FROM cust
)
SELECT customer_id, order_cnt, ROUND(total_amt, 2) AS total_amt,
       ROUND(avg_amt, 2) AS avg_amt, ROUND(total_discount, 2) AS total_discount,
       freq_tier, value_tier,
       ROUND(total_discount / NULLIF(total_amt + total_discount, 0) * 100, 2) AS discount_dependency_pct,
       CASE WHEN freq_tier >= 4 AND value_tier <= 2 THEN 'high_freq_low_value'
            WHEN freq_tier >= 4 AND value_tier >= 4 THEN 'core_customer'
            WHEN freq_tier <= 2 AND value_tier >= 4 THEN 'occasional_big_spender'
            ELSE 'regular' END AS cust_type
FROM scored
WHERE freq_tier >= 4 AND value_tier <= 2
ORDER BY order_cnt DESC
FETCH FIRST 300 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [097] 敏感度·价格弹性 | 电商 | 用户折扣敏感度分层与精准定价建议
-- ------------------------------------------------------------------------------
WITH cust AS (
    SELECT o.customer_id,
           COUNT(*)                                                     AS total_orders,
           SUM(CASE WHEN o.discount_amount > 0 THEN 1 ELSE 0 END)       AS discount_orders,
           SUM(o.discount_amount)                                       AS total_discount,
           SUM(o.pay_amount)                                            AS total_pay,
           AVG(o.discount_amount / NULLIF(o.pay_amount + o.discount_amount, 0)) AS avg_disc_rate
    FROM orders o
    WHERE o.status = 'completed'
      AND o.order_date >= (TRUNC(SYSDATE) - 180)
    GROUP BY o.customer_id
    HAVING COUNT(*) >= 3
)
SELECT CASE WHEN discount_orders / NULLIF(total_orders, 0) >= 0.8 THEN 'high_sensitivity'
            WHEN discount_orders / NULLIF(total_orders, 0) >= 0.5 THEN 'medium_sensitivity'
            WHEN discount_orders / NULLIF(total_orders, 0) >= 0.2 THEN 'low_sensitivity'
            ELSE 'price_insensitive' END AS sensitivity_level,
       COUNT(*)                                        AS cust_cnt,
       ROUND(AVG(avg_disc_rate) * 100, 2)              AS avg_discount_rate_pct,
       ROUND(AVG(total_pay), 2)                        AS avg_total_pay,
       ROUND(AVG(discount_orders / NULLIF(total_orders, 0)) * 100, 2) AS avg_disc_order_pct
FROM cust
GROUP BY CASE WHEN discount_orders / NULLIF(total_orders, 0) >= 0.8 THEN 'high_sensitivity'
              WHEN discount_orders / NULLIF(total_orders, 0) >= 0.5 THEN 'medium_sensitivity'
              WHEN discount_orders / NULLIF(total_orders, 0) >= 0.2 THEN 'low_sensitivity'
              ELSE 'price_insensitive' END
ORDER BY cust_cnt DESC;

-- ------------------------------------------------------------------------------
-- [098] 依赖·促销分析 | 电商 | 品类促销依赖度与正价销售能力
-- ------------------------------------------------------------------------------
WITH cat_sales AS (
    SELECT p.category_id,
           SUM(CASE WHEN o.discount_amount > 0 THEN oi.amount ELSE 0 END) AS promo_amt,
           SUM(CASE WHEN o.discount_amount = 0 THEN oi.amount ELSE 0 END) AS normal_amt,
           COUNT(DISTINCT CASE WHEN o.discount_amount > 0 THEN o.order_id END) AS promo_orders,
           COUNT(DISTINCT o.order_id) AS total_orders
    FROM order_items oi
    JOIN orders   o ON oi.order_id = o.order_id
    JOIN products p ON oi.product_id = p.product_id
    WHERE o.status = 'completed'
      AND o.order_date >= (TRUNC(SYSDATE) - 180)
    GROUP BY p.category_id
)
SELECT c.category_name,
       ROUND(cs.promo_amt, 2)  AS promo_amt,
       ROUND(cs.normal_amt, 2) AS normal_amt,
       ROUND(cs.promo_amt / NULLIF(cs.promo_amt + cs.normal_amt, 0) * 100, 2) AS promo_dependency_pct,
       ROUND(cs.promo_orders / NULLIF(cs.total_orders, 0) * 100, 2) AS promo_order_pct,
       CASE WHEN cs.promo_amt / NULLIF(cs.promo_amt + cs.normal_amt, 0) > 0.7 THEN 'heavy_promo'
            WHEN cs.promo_amt / NULLIF(cs.promo_amt + cs.normal_amt, 0) > 0.4 THEN 'moderate'
            ELSE 'healthy' END AS dependency_flag
FROM cat_sales cs
JOIN categories c ON cs.category_id = c.category_id
ORDER BY promo_dependency_pct DESC;

-- ------------------------------------------------------------------------------
-- [099] 效率·曝光转化 | 电商 | 商品曝光到购买的转化效率与优化排序
-- ------------------------------------------------------------------------------
WITH funnel AS (
    SELECT product_id,
           SUM(CASE WHEN event_type = 'view'        THEN 1 ELSE 0 END) AS expo_cnt,
           SUM(CASE WHEN event_type = 'detail_view' THEN 1 ELSE 0 END) AS detail_cnt,
           SUM(CASE WHEN event_type = 'add_cart'    THEN 1 ELSE 0 END) AS cart_cnt,
           SUM(CASE WHEN event_type = 'place_order' THEN 1 ELSE 0 END) AS order_cnt
    FROM user_events
    WHERE product_id IS NOT NULL
      AND event_time >= (TRUNC(SYSDATE) - 30)
    GROUP BY product_id
)
SELECT p.product_name, f.expo_cnt, f.detail_cnt, f.cart_cnt, f.order_cnt,
       ROUND(f.detail_cnt / NULLIF(f.expo_cnt, 0) * 100, 2)  AS expo2detail_pct,
       ROUND(f.cart_cnt / NULLIF(f.detail_cnt, 0) * 100, 2)  AS detail2cart_pct,
       ROUND(f.order_cnt / NULLIF(f.cart_cnt, 0) * 100, 2)   AS cart2order_pct,
       ROUND(f.order_cnt / NULLIF(f.expo_cnt, 0) * 100, 2)   AS overall_cvr_pct,
       CASE WHEN f.expo_cnt > 1000 AND f.order_cnt / NULLIF(f.expo_cnt, 0) < 0.01 THEN 'high_expo_low_cvr'
            WHEN f.expo_cnt < 100  AND f.order_cnt / NULLIF(f.expo_cnt, 0) > 0.05 THEN 'low_expo_high_cvr'
            ELSE 'normal' END AS optimization_hint
FROM funnel f
JOIN products p ON f.product_id = p.product_id
WHERE f.expo_cnt >= 50
ORDER BY overall_cvr_pct DESC
FETCH FIRST 100 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [100] 转化·详情页 | 电商 | 详情页浏览深度与加购转化关系
-- ------------------------------------------------------------------------------
WITH pd AS (
    SELECT session_id, product_id,
           COUNT(*) AS view_times,
           MIN(event_time) AS first_view,
           MAX(event_time) AS last_view
    FROM user_events
    WHERE event_type = 'detail_view'
      AND event_time >= (TRUNC(SYSDATE) - 30)
      AND product_id IS NOT NULL
    GROUP BY session_id, product_id
),
carted AS (
    SELECT DISTINCT session_id, product_id
    FROM user_events
    WHERE event_type = 'add_cart'
)
SELECT CASE WHEN pd.view_times = 1 THEN 'view_1'
            WHEN pd.view_times = 2 THEN 'view_2'
            WHEN pd.view_times BETWEEN 3 AND 5 THEN 'view_3_5'
            ELSE 'view_6plus' END AS view_depth,
       COUNT(*)                                                     AS session_cnt,
       SUM(CASE WHEN c.product_id IS NOT NULL THEN 1 ELSE 0 END)     AS carted_cnt,
       ROUND(SUM(CASE WHEN c.product_id IS NOT NULL THEN 1 ELSE 0 END)
             / NULLIF(COUNT(*), 0) * 100, 2)                         AS cart_rate_pct,
       ROUND(AVG(((pd.last_view - pd.first_view) * 1440)), 2)     AS avg_dwell_minutes
FROM pd
LEFT JOIN carted c ON pd.session_id = c.session_id AND pd.product_id = c.product_id
GROUP BY CASE WHEN pd.view_times = 1 THEN 'view_1'
              WHEN pd.view_times = 2 THEN 'view_2'
              WHEN pd.view_times BETWEEN 3 AND 5 THEN 'view_3_5'
              ELSE 'view_6plus' END
ORDER BY view_depth;

-- ------------------------------------------------------------------------------
-- [101] 画像·标签聚合 | 电商 | 用户多维度画像标签聚合
-- ------------------------------------------------------------------------------
WITH base AS (
    SELECT c.customer_id, c.customer_name, c.city, c."LEVEL", c.gender,
           (TRUNC(SYSDATE) - c.birth_date) / 365 AS age
    FROM customers c
),
beh AS (
    SELECT o.customer_id,
           COUNT(*)                       AS order_cnt,
           SUM(o.pay_amount)              AS total_amt,
           MAX(o.order_date)              AS last_order,
           COUNT(DISTINCT p.category_id)  AS cat_cnt,
           SUM(CASE WHEN o.discount_amount > 0 THEN 1 ELSE 0 END) AS promo_orders
    FROM orders o
    JOIN order_items oi ON o.order_id = oi.order_id
    JOIN products    p  ON oi.product_id = p.product_id
    WHERE o.status = 'completed'
    GROUP BY o.customer_id
)
SELECT b.customer_id, b.customer_name, b.city,
       CASE WHEN b.age < 25 THEN 'young'
            WHEN b.age < 35 THEN 'youth'
            WHEN b.age < 45 THEN 'middle'
            ELSE 'senior' END AS age_tag,
       CASE WHEN beh.total_amt >= 10000 THEN 'high_value'
            WHEN beh.total_amt >= 3000  THEN 'mid_value'
            ELSE 'low_value' END AS value_tag,
       CASE WHEN beh.cat_cnt >= 5 THEN 'wide_interest'
            WHEN beh.cat_cnt >= 3 THEN 'medium_interest'
            ELSE 'focused' END AS interest_tag,
       CASE WHEN beh.promo_orders / NULLIF(beh.order_cnt, 0) >= 0.6 THEN 'promo_driven' ELSE 'organic' END AS promo_tag,
       (TRUNC(SYSDATE) - beh.last_order) AS idle_days,
       ROUND(beh.total_amt, 2) AS total_amt
FROM base b
LEFT JOIN beh ON b.customer_id = beh.customer_id
WHERE beh.customer_id IS NOT NULL
ORDER BY beh.total_amt DESC
FETCH FIRST 500 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [102] 生命周期·阶段划分 | 电商 | 用户生命周期阶段（引入/成长/成熟/衰退/流失）
-- ------------------------------------------------------------------------------
WITH cust AS (
    SELECT customer_id,
           MIN(order_date) AS first_dt,
           MAX(order_date) AS last_dt,
           COUNT(*)        AS order_cnt,
           SUM(pay_amount) AS total_amt
    FROM orders
    WHERE status = 'completed'
    GROUP BY customer_id
),
stage AS (
    SELECT customer_id, order_cnt, total_amt,
           (TRUNC(SYSDATE) - first_dt) AS days_since_first,
           (TRUNC(SYSDATE) - last_dt)  AS days_since_last
    FROM cust
)
SELECT CASE WHEN days_since_first <= 30 AND order_cnt <= 2 THEN 'introduction'
            WHEN days_since_last <= 30 AND order_cnt BETWEEN 3 AND 10 THEN 'growth'
            WHEN days_since_last <= 30 AND order_cnt > 10 THEN 'mature'
            WHEN days_since_last BETWEEN 31 AND 90 THEN 'decline'
            WHEN days_since_last BETWEEN 91 AND 180 THEN 'sleeping'
            ELSE 'churned' END AS life_stage,
       COUNT(*)                        AS cust_cnt,
       ROUND(AVG(total_amt), 2)        AS avg_amt,
       ROUND(AVG(order_cnt), 2)        AS avg_orders,
       ROUND(SUM(total_amt) / NULLIF(SUM(SUM(total_amt)) OVER (), 0) * 100, 2) AS amt_share_pct
FROM stage
GROUP BY CASE WHEN days_since_first <= 30 AND order_cnt <= 2 THEN 'introduction'
              WHEN days_since_last <= 30 AND order_cnt BETWEEN 3 AND 10 THEN 'growth'
              WHEN days_since_last <= 30 AND order_cnt > 10 THEN 'mature'
              WHEN days_since_last BETWEEN 31 AND 90 THEN 'decline'
              WHEN days_since_last BETWEEN 91 AND 180 THEN 'sleeping'
              ELSE 'churned' END
ORDER BY cust_cnt DESC;

-- ------------------------------------------------------------------------------
-- [103] 渗透·品类扩展 | 电商 | 品类交叉渗透率与扩展机会矩阵
-- ------------------------------------------------------------------------------
WITH cust_cat AS (
    SELECT o.customer_id, p.category_id
    FROM orders o
    JOIN order_items oi ON o.order_id = oi.order_id
    JOIN products    p  ON oi.product_id = p.product_id
    WHERE o.status = 'completed'
    GROUP BY o.customer_id, p.category_id
),
pair AS (
    SELECT a.category_id AS cat_a, b.category_id AS cat_b,
           COUNT(DISTINCT a.customer_id) AS both_cnt
    FROM cust_cat a
    JOIN cust_cat b ON a.customer_id = b.customer_id AND a.category_id < b.category_id
    GROUP BY a.category_id, b.category_id
),
single AS (
    SELECT category_id, COUNT(*) AS cust_cnt FROM cust_cat GROUP BY category_id
)
SELECT ca.category_name AS category_a, cb.category_name AS category_b,
       pr.both_cnt,
       ROUND(pr.both_cnt / NULLIF(sa.cust_cnt, 0) * 100, 2) AS penetration_a_pct,
       ROUND(pr.both_cnt / NULLIF(sb.cust_cnt, 0) * 100, 2) AS penetration_b_pct,
       ROUND(pr.both_cnt / NULLIF(LEAST(sa.cust_cnt, sb.cust_cnt), 0) * 100, 2) AS max_opportunity_pct
FROM pair pr
JOIN categories ca ON pr.cat_a = ca.category_id
JOIN categories cb ON pr.cat_b = cb.category_id
JOIN single sa ON pr.cat_a = sa.category_id
JOIN single sb ON pr.cat_b = sb.category_id
ORDER BY pr.both_cnt DESC
FETCH FIRST 100 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [104] 新品·成功率 | 电商 | 新品上市成功率与早期表现评估
-- ------------------------------------------------------------------------------
WITH np AS (
    SELECT p.product_id, p.product_name, p.category_id, p.launch_date
    FROM products p
    WHERE p.launch_date >= (TRUNC(SYSDATE) - 365)
),
perf AS (
    SELECT n.product_id,
           SUM(oi.amount)   AS amt_90d,
           SUM(oi.quantity) AS qty_90d,
           COUNT(DISTINCT o.customer_id) AS buyers
    FROM np n
    LEFT JOIN order_items oi ON n.product_id = oi.product_id
    LEFT JOIN orders o ON oi.order_id = o.order_id AND o.status = 'completed'
         AND o.order_date >= n.launch_date
         AND o.order_date <= (n.launch_date + 90)
    GROUP BY n.product_id
)
SELECT c.category_name,
       COUNT(*)                                              AS new_sku_cnt,
       SUM(CASE WHEN pf.amt_90d >= 10000 THEN 1 ELSE 0 END)  AS success_cnt,
       SUM(CASE WHEN pf.amt_90d IS NULL OR pf.amt_90d = 0 THEN 1 ELSE 0 END) AS zero_sale_cnt,
       ROUND(SUM(CASE WHEN pf.amt_90d >= 10000 THEN 1 ELSE 0 END)
             / NULLIF(COUNT(*), 0) * 100, 2)                 AS success_rate_pct,
       ROUND(AVG(pf.amt_90d), 2)                             AS avg_amt_90d,
       ROUND(AVG(pf.buyers), 1)                              AS avg_buyers
FROM np
JOIN perf pf ON np.product_id = pf.product_id
JOIN categories c ON np.category_id = c.category_id
GROUP BY c.category_name
ORDER BY success_rate_pct DESC;

-- ------------------------------------------------------------------------------
-- [105] 长尾·贡献分析 | 电商 | 长尾商品贡献度与集中度分析
-- ------------------------------------------------------------------------------
WITH prod AS (
    SELECT p.product_id, p.product_name, SUM(oi.amount) AS amt
    FROM order_items oi
    JOIN products p ON oi.product_id = p.product_id
    JOIN orders   o ON oi.order_id = o.order_id
    WHERE o.status = 'completed'
    GROUP BY p.product_id, p.product_name
),
rk AS (
    SELECT product_id, product_name, amt,
           ROW_NUMBER() OVER (ORDER BY amt DESC) AS rn,
           COUNT(*) OVER ()                      AS total_sku,
           SUM(amt) OVER (ORDER BY amt DESC ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS cum_amt,
           SUM(amt) OVER ()                                                                    AS total_amt
    FROM prod
)
SELECT CASE WHEN rn <= total_sku * 0.1 THEN 'head_10pct'
            WHEN rn <= total_sku * 0.5 THEN 'body_40pct'
            ELSE 'tail_50pct' END AS sku_segment,
       COUNT(*)                                        AS sku_cnt,
       ROUND(SUM(amt), 2)                              AS total_amt,
       ROUND(SUM(amt) / NULLIF(MAX(total_amt), 0) * 100, 2) AS amt_share_pct,
       ROUND(AVG(amt), 2)                              AS avg_amt_per_sku
FROM rk
GROUP BY CASE WHEN rn <= total_sku * 0.1 THEN 'head_10pct'
              WHEN rn <= total_sku * 0.5 THEN 'body_40pct'
              ELSE 'tail_50pct' END
ORDER BY amt_share_pct DESC;

-- ------------------------------------------------------------------------------
-- [106] 预警·评分下滑 | 电商 | 商品评分下滑趋势预警
-- ------------------------------------------------------------------------------
WITH mr AS (
    SELECT product_id, TO_CHAR(created_at, 'YYYY-MM') AS ym,
           AVG(rating) AS avg_rating, COUNT(*) AS review_cnt
    FROM reviews
    GROUP BY product_id, TO_CHAR(created_at, 'YYYY-MM')
),
cmp AS (
    SELECT product_id, ym, avg_rating, review_cnt,
           LAG(avg_rating) OVER (PARTITION BY product_id ORDER BY ym) AS prev_rating,
           AVG(avg_rating) OVER (PARTITION BY product_id)             AS hist_avg_rating
    FROM mr
)
SELECT p.product_name, c.ym, ROUND(c.avg_rating, 2) AS avg_rating,
       ROUND(c.prev_rating, 2)      AS prev_rating,
       ROUND(c.hist_avg_rating, 2)  AS hist_avg_rating,
       c.review_cnt,
       ROUND(c.avg_rating - c.hist_avg_rating, 2) AS vs_hist_avg,
       CASE WHEN c.avg_rating < c.hist_avg_rating - 0.5 AND c.review_cnt >= 5 THEN 'rating_alert'
            WHEN c.avg_rating < 3.5 THEN 'low_rating'
            ELSE 'normal' END AS alert_flag
FROM cmp c
JOIN products p ON c.product_id = p.product_id
WHERE c.prev_rating IS NOT NULL
  AND c.avg_rating < c.hist_avg_rating - 0.3
ORDER BY vs_hist_avg ASC
FETCH FIRST 150 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [107] 分布·周转天数 | 电商 | 库存周转天数分布与呆滞库存识别
-- ------------------------------------------------------------------------------
WITH turn AS (
    SELECT i.product_id,
           SUM(i.quantity) AS stock_qty,
           SUM(i.safety_stock) AS safety_qty
    FROM inventory i
    GROUP BY i.product_id
),
sold AS (
    SELECT product_id, SUM(quantity) AS qty_180d
    FROM order_items oi
    JOIN orders o ON oi.order_id = o.order_id
    WHERE o.status = 'completed'
      AND o.order_date >= (TRUNC(SYSDATE) - 180)
    GROUP BY product_id
),
calc AS (
    SELECT t.product_id, t.stock_qty, COALESCE(s.qty_180d, 0) AS qty_180d,
           CASE WHEN COALESCE(s.qty_180d, 0) = 0 THEN 9999
                ELSE t.stock_qty / (s.qty_180d / 180.0) END AS turnover_days
    FROM turn t
    LEFT JOIN sold s ON t.product_id = s.product_id
)
SELECT CASE WHEN turnover_days >= 9999 THEN 'no_sales'
            WHEN turnover_days < 15  THEN 'fast_0_15'
            WHEN turnover_days < 30  THEN 'normal_15_30'
            WHEN turnover_days < 60  THEN 'slow_30_60'
            WHEN turnover_days < 120 THEN 'dull_60_120'
            ELSE 'dead_120plus' END AS turnover_band,
       COUNT(*)                          AS sku_cnt,
       SUM(stock_qty)                    AS total_stock,
       ROUND(AVG(turnover_days), 1)      AS avg_days,
       ROUND(SUM(stock_qty) / NULLIF(SUM(SUM(stock_qty)) OVER (), 0) * 100, 2) AS stock_share_pct
FROM calc
GROUP BY CASE WHEN turnover_days >= 9999 THEN 'no_sales'
              WHEN turnover_days < 15  THEN 'fast_0_15'
              WHEN turnover_days < 30  THEN 'normal_15_30'
              WHEN turnover_days < 60  THEN 'slow_30_60'
              WHEN turnover_days < 120 THEN 'dull_60_120'
              ELSE 'dead_120plus' END
ORDER BY avg_days DESC;

-- ------------------------------------------------------------------------------
-- [108] 资金·回款周期 | 电商 | 订单到回款周期分析与资金占用
-- ------------------------------------------------------------------------------
WITH pr AS (
    SELECT o.order_id, o.order_date, o.pay_amount, o.channel,
           MIN(p.pay_date) AS first_pay_date,
           SUM(p.pay_amount) AS paid_total
    FROM orders o
    LEFT JOIN payments p ON o.order_id = p.order_id AND p.status = 'success'
    WHERE o.status = 'completed'
      AND o.order_date >= (TRUNC(SYSDATE) - 180)
    GROUP BY o.order_id, o.order_date, o.pay_amount, o.channel
)
SELECT channel,
       COUNT(*)                                                       AS order_cnt,
       ROUND(AVG((first_pay_date - order_date)), 2)          AS avg_pay_lag_days,
       MAX((first_pay_date - order_date))                    AS max_pay_lag_days,
       SUM(CASE WHEN (first_pay_date - order_date) <= 0 THEN 1 ELSE 0 END) AS same_day_paid,
       ROUND(SUM(CASE WHEN (first_pay_date - order_date) <= 0 THEN 1 ELSE 0 END)
             / NULLIF(COUNT(*), 0) * 100, 2)                          AS same_day_pct,
       SUM(CASE WHEN first_pay_date IS NULL THEN 1 ELSE 0 END)        AS unpaid_cnt,
       ROUND(SUM(pay_amount - COALESCE(paid_total, 0)), 2)            AS outstanding_amt
FROM pr
GROUP BY channel
ORDER BY avg_pay_lag_days DESC;

-- ------------------------------------------------------------------------------
-- [109] 看板·经营指标 | 电商 | 核心经营指标日看板（多指标横向对比）
-- ------------------------------------------------------------------------------
SELECT CAST(o.order_date AS DATE) AS dt,
       COUNT(DISTINCT o.order_id)                    AS order_cnt,
       COUNT(DISTINCT o.customer_id)                 AS pay_uv,
       ROUND(SUM(o.pay_amount), 2)                   AS gmv,
       ROUND(AVG(o.pay_amount), 2)                   AS avg_ticket,
       ROUND(SUM(o.discount_amount), 2)              AS discount,
       ROUND(SUM(o.discount_amount)
             / NULLIF(SUM(o.pay_amount + o.discount_amount), 0) * 100, 2) AS discount_rate_pct,
       SUM(CASE WHEN o.status = 'cancelled' THEN 1 ELSE 0 END) AS cancelled_cnt,
       ROUND(SUM(CASE WHEN o.status = 'cancelled' THEN 1 ELSE 0 END)
             / NULLIF(COUNT(*), 0) * 100, 2)         AS cancel_rate_pct,
       ROUND(SUM(o.pay_amount) - LAG(SUM(o.pay_amount)) OVER (ORDER BY CAST(o.order_date AS DATE)), 2) AS gmv_dod
FROM orders o
WHERE o.order_date >= (TRUNC(SYSDATE) - 30)
GROUP BY CAST(o.order_date AS DATE)
ORDER BY dt DESC;

-- ------------------------------------------------------------------------------
-- [110] 健康度·增长质量 | 电商 | 用户增长健康度（新增/活跃/留存/流失全景）
-- ------------------------------------------------------------------------------
WITH reg AS (
    SELECT CAST(register_date AS DATE) AS dt, COUNT(*) AS new_cust
    FROM customers
    WHERE register_date >= (TRUNC(SYSDATE) - 90)
    GROUP BY CAST(register_date AS DATE)
),
act AS (
    SELECT CAST(order_date AS DATE) AS dt, COUNT(DISTINCT customer_id) AS active_cust
    FROM orders
    WHERE status = 'completed'
      AND order_date >= (TRUNC(SYSDATE) - 90)
    GROUP BY CAST(order_date AS DATE)
),
dates AS (
    SELECT dt FROM reg
    UNION
    SELECT dt FROM act
)
SELECT d.dt,
       COALESCE(r.new_cust, 0)    AS new_cust,
       COALESCE(a.active_cust, 0) AS active_cust,
       ROUND(COALESCE(a.active_cust, 0) - COALESCE(r.new_cust, 0), 0) AS returning_cust,
       ROUND(AVG(COALESCE(r.new_cust, 0)) OVER (ORDER BY d.dt ROWS BETWEEN 6 PRECEDING AND CURRENT ROW), 1) AS new_cust_ma7,
       ROUND(AVG(COALESCE(a.active_cust, 0)) OVER (ORDER BY d.dt ROWS BETWEEN 6 PRECEDING AND CURRENT ROW), 1) AS active_ma7,
       ROUND((COALESCE(a.active_cust, 0) - COALESCE(r.new_cust, 0))
             / NULLIF(COALESCE(a.active_cust, 0), 0) * 100, 2) AS returning_pct
FROM dates d
LEFT JOIN reg r ON d.dt = r.dt
LEFT JOIN act a ON d.dt = a.dt
ORDER BY d.dt;

-- ------------------------------------------------------------------------------
-- [111] 分层·账户余额 | 金融 | 账户余额分层与客户资产分布
-- ------------------------------------------------------------------------------
SELECT CASE WHEN balance >= 1000000 THEN 'private_banking'
            WHEN balance >= 100000  THEN 'vip'
            WHEN balance >= 10000   THEN 'gold'
            WHEN balance >= 1000    THEN 'standard'
            ELSE 'basic' END AS balance_tier,
       COUNT(*)                    AS account_cnt,
       ROUND(SUM(balance), 2)      AS total_balance,
       ROUND(AVG(balance), 2)      AS avg_balance,
       ROUND(MIN(balance), 2)      AS min_balance,
       ROUND(MAX(balance), 2)      AS max_balance,
       ROUND(SUM(balance) / NULLIF(SUM(SUM(balance)) OVER (), 0) * 100, 2) AS balance_share_pct
FROM accounts
WHERE status = 'active'
GROUP BY CASE WHEN balance >= 1000000 THEN 'private_banking'
              WHEN balance >= 100000  THEN 'vip'
              WHEN balance >= 10000   THEN 'gold'
              WHEN balance >= 1000    THEN 'standard'
              ELSE 'basic' END
ORDER BY total_balance DESC;

-- ------------------------------------------------------------------------------
-- [112] 窗口·余额变动 | 金融 | 账户月度余额均值与环比变动
-- ------------------------------------------------------------------------------
WITH eod AS (
    SELECT account_id, CAST(txn_date AS DATE) AS dt, balance_after,
           ROW_NUMBER() OVER (PARTITION BY account_id, CAST(txn_date AS DATE)
                              ORDER BY txn_date DESC) AS rn
    FROM transactions
    WHERE txn_date >= (TRUNC(SYSDATE) - 365)
),
daily AS (
    SELECT account_id, dt, balance_after FROM eod WHERE rn = 1
),
m AS (
    SELECT account_id, TO_CHAR(dt, 'YYYY-MM') AS ym,
           AVG(balance_after) AS avg_bal,
           MAX(balance_after) AS max_bal,
           MIN(balance_after) AS min_bal
    FROM daily
    GROUP BY account_id, TO_CHAR(dt, 'YYYY-MM')
)
SELECT account_id, ym,
       ROUND(avg_bal, 2) AS avg_bal,
       ROUND(LAG(avg_bal) OVER (PARTITION BY account_id ORDER BY ym), 2) AS prev_avg_bal,
       ROUND(avg_bal - LAG(avg_bal) OVER (PARTITION BY account_id ORDER BY ym), 2) AS delta_bal,
       ROUND((avg_bal - LAG(avg_bal) OVER (PARTITION BY account_id ORDER BY ym))
             / NULLIF(LAG(avg_bal) OVER (PARTITION BY account_id ORDER BY ym), 0) * 100, 2) AS change_pct,
       ROUND(max_bal - min_bal, 2) AS intra_month_volatility,
       CASE WHEN avg_bal < LAG(avg_bal) OVER (PARTITION BY account_id ORDER BY ym) * 0.5
            THEN 'sharp_drop' ELSE 'normal' END AS move_flag
FROM m
ORDER BY account_id, ym;

-- ------------------------------------------------------------------------------
-- [113] TOPN·大额交易 | 金融 | 各分支行大额交易 TopN 与占比
-- ------------------------------------------------------------------------------
WITH big AS (
    SELECT t.txn_id, t.account_id, t.amount, t.txn_date, t.txn_type,
           a.branch_id, b.branch_name,
           ROW_NUMBER() OVER (PARTITION BY a.branch_id ORDER BY t.amount DESC) AS rn,
           SUM(t.amount) OVER (PARTITION BY a.branch_id)                       AS branch_txn_total
    FROM transactions t
    JOIN accounts a ON t.account_id = a.account_id
    JOIN branches b ON a.branch_id = b.branch_id
    WHERE t.txn_date >= (TRUNC(SYSDATE) - 30)
      AND t.amount >= 100000
)
SELECT branch_name, txn_id, account_id, txn_type,
       ROUND(amount, 2) AS amount, txn_date, rn AS branch_rank,
       ROUND(amount / NULLIF(branch_txn_total, 0) * 100, 2) AS pct_of_branch
FROM big
WHERE rn <= 5
ORDER BY branch_name, rn;

-- ------------------------------------------------------------------------------
-- [114] 异常·交易检测 | 金融 | 账户交易金额异常检测（偏离历史均值）
-- ------------------------------------------------------------------------------
WITH acct_stat AS (
    SELECT account_id,
           AVG(amount) AS avg_amt,
           COUNT(*)    AS txn_cnt
    FROM transactions
    WHERE txn_date >= (TRUNC(SYSDATE) - 180)
    GROUP BY account_id
    HAVING COUNT(*) >= 10
),
tagged AS (
    SELECT t.txn_id, t.account_id, t.amount, t.txn_date, t.txn_type,
           s.avg_amt,
           (t.amount - s.avg_amt) / NULLIF(s.avg_amt, 0) AS deviation
    FROM transactions t
    JOIN acct_stat s ON t.account_id = s.account_id
    WHERE t.txn_date >= (TRUNC(SYSDATE) - 7)
)
SELECT txn_id, account_id, txn_type,
       ROUND(amount, 2)      AS amount,
       ROUND(avg_amt, 2)     AS hist_avg_amt,
       ROUND(deviation, 2)   AS deviation_multiple,
       CASE WHEN deviation >= 5 THEN 'high_risk'
            WHEN deviation >= 3 THEN 'medium_risk'
            WHEN deviation <= -0.9 THEN 'unusual_small'
            ELSE 'normal' END AS anomaly_level
FROM tagged
WHERE deviation >= 3 OR deviation <= -0.9
ORDER BY deviation DESC
FETCH FIRST 500 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [115] 频次·频繁交易 | 金融 | 短时高频交易识别（时间窗口计数）
-- ------------------------------------------------------------------------------
WITH hourly AS (
    SELECT account_id,
           TO_CHAR(txn_date, 'YYYY-MM-DD HH24') AS hh,
           COUNT(*)      AS cnt,
           SUM(amount)   AS amt
    FROM transactions
    WHERE txn_date >= (TRUNC(SYSDATE) - 7)
    GROUP BY account_id, TO_CHAR(txn_date, 'YYYY-MM-DD HH24')
),
agg AS (
    SELECT account_id,
           SUM(cnt) AS txn_cnt,
           SUM(amt) AS total_amount,
           MAX(cnt) AS max_txn_per_hour,
           COUNT(*) AS active_hours,
           MIN(hh)  AS first_active_hour,
           MAX(hh)  AS last_active_hour
    FROM hourly
    GROUP BY account_id
)
SELECT account_id, txn_cnt,
       ROUND(total_amount, 2)                            AS total_amount,
       max_txn_per_hour, active_hours,
       first_active_hour, last_active_hour,
       ROUND(total_amount / NULLIF(txn_cnt, 0), 2)       AS avg_amount,
       ROUND(CAST(txn_cnt AS DECIMAL(18,2)) / NULLIF(active_hours, 0), 2) AS avg_txn_per_active_hour,
       CASE WHEN max_txn_per_hour >= 20 THEN 'extreme_frequent'
            WHEN max_txn_per_hour >= 10 THEN 'frequent'
            ELSE 'normal' END AS frequency_flag
FROM agg
WHERE txn_cnt >= 30
ORDER BY max_txn_per_hour DESC
FETCH FIRST 300 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [116] 集中度·交易对手 | 金融 | 客户交易对手集中度分析
-- ------------------------------------------------------------------------------
WITH cp AS (
    SELECT a.cust_id, t.counterparty,
           COUNT(*)          AS txn_cnt,
           SUM(t.amount)     AS total_amt
    FROM transactions t
    JOIN accounts a ON t.account_id = a.account_id
    WHERE t.txn_date >= (TRUNC(SYSDATE) - 180)
      AND t.counterparty IS NOT NULL
    GROUP BY a.cust_id, t.counterparty
),
ranked AS (
    SELECT cust_id, counterparty, txn_cnt, total_amt,
           SUM(total_amt) OVER (PARTITION BY cust_id) AS cust_total,
           ROW_NUMBER() OVER (PARTITION BY cust_id ORDER BY total_amt DESC) AS rn
    FROM cp
)
SELECT cust_id, counterparty, txn_cnt, ROUND(total_amt, 2) AS total_amt,
       ROUND(total_amt / NULLIF(cust_total, 0) * 100, 2) AS concentration_pct,
       rn AS top_rank,
       CASE WHEN total_amt / NULLIF(cust_total, 0) > 0.8 THEN 'highly_concentrated'
            WHEN total_amt / NULLIF(cust_total, 0) > 0.5 THEN 'concentrated'
            ELSE 'diversified' END AS concentration_flag
FROM ranked
WHERE rn <= 5
ORDER BY cust_id, rn;

-- ------------------------------------------------------------------------------
-- [117] 反洗钱·资金回流 | 金融 | 快进快出（资金短期回流）可疑模式识别
-- ------------------------------------------------------------------------------
WITH flow AS (
    SELECT a.cust_id, t.account_id, t.txn_id, t.txn_date, t.txn_type, t.amount,
           LEAD(t.txn_type) OVER (PARTITION BY t.account_id ORDER BY t.txn_date) AS next_type,
           LEAD(t.txn_date) OVER (PARTITION BY t.account_id ORDER BY t.txn_date) AS next_date,
           LEAD(t.amount)   OVER (PARTITION BY t.account_id ORDER BY t.txn_date) AS next_amount
    FROM transactions t
    JOIN accounts a ON t.account_id = a.account_id
    WHERE t.txn_date >= (TRUNC(SYSDATE) - 30)
)
SELECT cust_id, account_id, txn_id,
       ROUND(amount, 2)       AS out_amount,
       ROUND(next_amount, 2)  AS in_amount,
       txn_date, next_date,
       ((next_date - txn_date) * 24) AS gap_hours,
       ROUND(LEAST(amount, next_amount) / NULLIF(GREATEST(amount, next_amount), 0) * 100, 2) AS match_pct,
       CASE WHEN txn_type = 'transfer_out' AND next_type = 'transfer_in'
                 AND ((next_date - txn_date) * 24) <= 24
                 AND LEAST(amount, next_amount) / NULLIF(GREATEST(amount, next_amount), 0) >= 0.9
            THEN 'round_trip_suspect' ELSE 'normal' END AS pattern_flag
FROM flow
WHERE txn_type = 'transfer_out' AND next_type = 'transfer_in'
  AND ((next_date - txn_date) * 24) <= 24
ORDER BY match_pct DESC
FETCH FIRST 300 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [118] 活跃·账户状态 | 金融 | 账户休眠识别与激活转化分析
-- ------------------------------------------------------------------------------
WITH last_txn AS (
    SELECT account_id, MAX(txn_date) AS last_dt, COUNT(*) AS txn_cnt
    FROM transactions
    GROUP BY account_id
)
SELECT a.account_type,
       COUNT(*)                                                   AS account_cnt,
       SUM(CASE WHEN (TRUNC(SYSDATE) - lt.last_dt) <= 30  THEN 1 ELSE 0 END) AS active_30d,
       SUM(CASE WHEN (TRUNC(SYSDATE) - lt.last_dt) BETWEEN 31 AND 90 THEN 1 ELSE 0 END) AS low_active,
       SUM(CASE WHEN (TRUNC(SYSDATE) - lt.last_dt) BETWEEN 91 AND 365 THEN 1 ELSE 0 END) AS dormant,
       SUM(CASE WHEN lt.account_id IS NULL
                     OR (TRUNC(SYSDATE) - lt.last_dt) > 365 THEN 1 ELSE 0 END) AS inactive,
       ROUND(AVG((TRUNC(SYSDATE) - lt.last_dt)), 1) AS avg_idle_days,
       ROUND(SUM(CASE WHEN (TRUNC(SYSDATE) - lt.last_dt) <= 30 THEN 1 ELSE 0 END)
             / NULLIF(COUNT(*), 0) * 100, 2) AS active_rate_pct
FROM accounts a
LEFT JOIN last_txn lt ON a.account_id = lt.account_id
WHERE a.status = 'active'
GROUP BY a.account_type
ORDER BY active_rate_pct DESC;

-- ------------------------------------------------------------------------------
-- [119] 汇总·客户资产 | 金融 | 客户资产全景（存款+理财+账户余额）
-- ------------------------------------------------------------------------------
WITH acct AS (
    SELECT cust_id, SUM(balance) AS acct_balance, COUNT(*) AS acct_cnt
    FROM accounts WHERE status = 'active' GROUP BY cust_id
),
dep AS (
    SELECT cust_id, SUM(amount) AS deposit_amt, COUNT(*) AS dep_cnt
    FROM deposits WHERE status = 'active' GROUP BY cust_id
),
fund AS (
    SELECT cust_id, SUM(cost_amount) AS fund_cost, COUNT(*) AS fund_cnt
    FROM holdings GROUP BY cust_id
),
nav AS (
    SELECT h.cust_id, SUM(h.shares * n.nav) AS fund_market_value
    FROM holdings h
    JOIN fund_nav n ON h.fund_id = n.fund_id
    WHERE n.nav_date = (SELECT MAX(nav_date) FROM fund_nav n2 WHERE n2.fund_id = n.fund_id)
    GROUP BY h.cust_id
)
SELECT c.cust_id, c.cust_name, c.cust_type, c.risk_level,
       ROUND(COALESCE(a.acct_balance, 0), 2)    AS acct_balance,
       ROUND(COALESCE(d.deposit_amt, 0), 2)     AS deposit_amt,
       ROUND(COALESCE(nv.fund_market_value, 0), 2) AS fund_value,
       ROUND(COALESCE(a.acct_balance, 0) + COALESCE(d.deposit_amt, 0)
             + COALESCE(nv.fund_market_value, 0), 2) AS total_aum,
       COALESCE(a.acct_cnt, 0) + COALESCE(d.dep_cnt, 0) + COALESCE(f.fund_cnt, 0) AS product_cnt
FROM fin_customers c
LEFT JOIN acct a  ON c.cust_id = a.cust_id
LEFT JOIN dep  d  ON c.cust_id = d.cust_id
LEFT JOIN fund f  ON c.cust_id = f.cust_id
LEFT JOIN nav  nv ON c.cust_id = nv.cust_id
ORDER BY total_aum DESC
FETCH FIRST 500 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [120] 帕累托·AUM贡献 | 金融 | 客户 AUM 排名与帕累托贡献分析
-- ------------------------------------------------------------------------------
WITH cust_aum AS (
    SELECT cust_id, SUM(balance) AS aum
    FROM accounts WHERE status = 'active' GROUP BY cust_id
),
ordered AS (
    SELECT cust_id, aum,
           ROW_NUMBER() OVER (ORDER BY aum DESC) AS rn,
           COUNT(*) OVER ()                      AS total_cust,
           SUM(aum) OVER (ORDER BY aum DESC
                ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS cum_aum,
           SUM(aum) OVER ()                                       AS total_aum
    FROM cust_aum
)
SELECT c.cust_name, o.rn, ROUND(o.aum, 2) AS aum,
       ROUND(o.rn / NULLIF(o.total_cust, 0) * 100, 2)     AS cust_pct,
       ROUND(o.cum_aum / NULLIF(o.total_aum, 0) * 100, 2) AS cum_aum_pct,
       CASE WHEN o.cum_aum / NULLIF(o.total_aum, 0) <= 0.5 THEN 'top50'
            WHEN o.cum_aum / NULLIF(o.total_aum, 0) <= 0.8 THEN 'top80'
            ELSE 'long_tail' END AS value_segment
FROM ordered o
JOIN fin_customers c ON o.cust_id = c.cust_id
WHERE o.cum_aum / NULLIF(o.total_aum, 0) <= 0.8001
ORDER BY o.rn;

-- ------------------------------------------------------------------------------
-- [121] 结构·存款分析 | 金融 | 存款期限结构与到期分布
-- ------------------------------------------------------------------------------
SELECT CASE WHEN MONTHS_BETWEEN(maturity_date, start_date) <= 3  THEN 'demand_like'
            WHEN MONTHS_BETWEEN(maturity_date, start_date) <= 12 THEN 'short_term'
            WHEN MONTHS_BETWEEN(maturity_date, start_date) <= 36 THEN 'medium_term'
            ELSE 'long_term' END AS term_band,
       COUNT(*)                    AS dep_cnt,
       ROUND(SUM(amount), 2)       AS total_amt,
       ROUND(AVG(amount), 2)       AS avg_amt,
       ROUND(AVG(rate) * 100, 3)   AS avg_rate_pct,
       ROUND(SUM(amount * rate) / NULLIF(SUM(amount), 0) * 100, 3) AS weighted_rate_pct,
       ROUND(SUM(amount) / NULLIF(SUM(SUM(amount)) OVER (), 0) * 100, 2) AS amt_share_pct
FROM deposits
WHERE status = 'active'
GROUP BY CASE WHEN MONTHS_BETWEEN(maturity_date, start_date) <= 3  THEN 'demand_like'
              WHEN MONTHS_BETWEEN(maturity_date, start_date) <= 12 THEN 'short_term'
              WHEN MONTHS_BETWEEN(maturity_date, start_date) <= 36 THEN 'medium_term'
              ELSE 'long_term' END
ORDER BY total_amt DESC;

-- ------------------------------------------------------------------------------
-- [122] 到期·存款流失 | 金融 | 存款到期分布与续存流失预警
-- ------------------------------------------------------------------------------
WITH mat AS (
    SELECT d.deposit_id, d.cust_id, d.amount, d.rate, d.maturity_date,
           (d.maturity_date - TRUNC(SYSDATE)) AS days_to_maturity
    FROM deposits d
    WHERE d.status = 'active'
),
cust_other AS (
    SELECT cust_id, COUNT(*) AS other_dep_cnt
    FROM deposits WHERE status = 'active' GROUP BY cust_id
)
SELECT CASE WHEN m.days_to_maturity < 0  THEN 'overdue'
            WHEN m.days_to_maturity <= 30  THEN 'maturing_30d'
            WHEN m.days_to_maturity <= 90  THEN 'maturing_90d'
            ELSE 'long_term' END AS maturity_bucket,
       COUNT(*)                    AS dep_cnt,
       ROUND(SUM(m.amount), 2)     AS total_amt,
       ROUND(AVG(m.rate) * 100, 3) AS avg_rate_pct,
       SUM(CASE WHEN COALESCE(co.other_dep_cnt, 0) <= 1 THEN 1 ELSE 0 END) AS single_dep_cnt,
       ROUND(SUM(CASE WHEN COALESCE(co.other_dep_cnt, 0) <= 1 THEN m.amount ELSE 0 END), 2) AS at_risk_amt
FROM mat m
LEFT JOIN cust_other co ON m.cust_id = co.cust_id
GROUP BY CASE WHEN m.days_to_maturity < 0  THEN 'overdue'
              WHEN m.days_to_maturity <= 30  THEN 'maturing_30d'
              WHEN m.days_to_maturity <= 90  THEN 'maturing_90d'
              ELSE 'long_term' END
ORDER BY total_amt DESC;

-- ------------------------------------------------------------------------------
-- [123] 结构·存贷分析 | 金融 | 分支行存贷比与资金运用效率
-- ------------------------------------------------------------------------------
WITH dep AS (
    SELECT branch_id, SUM(amount) AS deposit_amt
    FROM deposits WHERE status = 'active' GROUP BY branch_id
),
ln AS (
    SELECT branch_id, SUM(loan_amount) AS loan_amt
    FROM loans WHERE status IN ('active', 'overdue') GROUP BY branch_id
)
SELECT b.branch_name, b.city, b.region,
       ROUND(COALESCE(d.deposit_amt, 0), 2) AS deposit_amt,
       ROUND(COALESCE(l.loan_amt, 0), 2)    AS loan_amt,
       ROUND(COALESCE(l.loan_amt, 0) / NULLIF(COALESCE(d.deposit_amt, 0), 0) * 100, 2) AS ldr_pct,
       ROUND(COALESCE(d.deposit_amt, 0) - COALESCE(l.loan_amt, 0), 2) AS surplus_amt,
       CASE WHEN COALESCE(l.loan_amt, 0) / NULLIF(COALESCE(d.deposit_amt, 0), 0) > 0.75 THEN 'high_ldr'
            WHEN COALESCE(l.loan_amt, 0) / NULLIF(COALESCE(d.deposit_amt, 0), 0) < 0.3 THEN 'low_efficiency'
            ELSE 'balanced' END AS ldr_flag
FROM branches b
LEFT JOIN dep d ON b.branch_id = d.branch_id
LEFT JOIN ln  l ON b.branch_id = l.branch_id
ORDER BY ldr_pct DESC;

-- ------------------------------------------------------------------------------
-- [124] 分层·客户价值 | 金融 | 客户价值分层（按 AUM 与产品持有数）
-- ------------------------------------------------------------------------------
WITH aum AS (
    SELECT cust_id, SUM(balance) AS total_aum, COUNT(*) AS acct_cnt
    FROM accounts WHERE status = 'active' GROUP BY cust_id
),
prod AS (
    SELECT cust_id,
           COUNT(DISTINCT CASE WHEN product = 'deposit' THEN 1 END) AS dep_cnt,
           COUNT(DISTINCT CASE WHEN product = 'fund'    THEN 1 END) AS fund_cnt,
           COUNT(DISTINCT CASE WHEN product = 'loan'    THEN 1 END) AS loan_cnt
    FROM (
        SELECT cust_id, 'deposit' AS product FROM deposits WHERE status = 'active'
        UNION ALL
        SELECT cust_id, 'fund'    FROM holdings
        UNION ALL
        SELECT cust_id, 'loan'    FROM loans WHERE status IN ('active', 'overdue')
    ) p
    GROUP BY cust_id
)
SELECT CASE WHEN a.total_aum >= 1000000 THEN 'private'
            WHEN a.total_aum >= 300000  THEN 'wealth'
            WHEN a.total_aum >= 50000   THEN 'affluent'
            WHEN a.total_aum >= 5000    THEN 'mass'
            ELSE 'basic' END AS value_tier,
       COUNT(*)                                        AS cust_cnt,
       ROUND(SUM(a.total_aum), 2)                      AS total_aum,
       ROUND(AVG(a.total_aum), 2)                      AS avg_aum,
       ROUND(AVG(COALESCE(p.dep_cnt, 0) + COALESCE(p.fund_cnt, 0) + COALESCE(p.loan_cnt, 0)), 2) AS avg_products,
       ROUND(SUM(a.total_aum) / NULLIF(SUM(SUM(a.total_aum)) OVER (), 0) * 100, 2) AS aum_share_pct
FROM aum a
LEFT JOIN prod p ON a.cust_id = p.cust_id
GROUP BY CASE WHEN a.total_aum >= 1000000 THEN 'private'
              WHEN a.total_aum >= 300000  THEN 'wealth'
              WHEN a.total_aum >= 50000   THEN 'affluent'
              WHEN a.total_aum >= 5000    THEN 'mass'
              ELSE 'basic' END
ORDER BY avg_aum DESC;

-- ------------------------------------------------------------------------------
-- [125] 交叉·产品持有 | 金融 | 客户产品持有交叉分析与交叉销售机会
-- ------------------------------------------------------------------------------
WITH hold AS (
    SELECT cust_id,
           MAX(CASE WHEN product = 'deposit' THEN 1 ELSE 0 END) AS has_deposit,
           MAX(CASE WHEN product = 'fund'    THEN 1 ELSE 0 END) AS has_fund,
           MAX(CASE WHEN product = 'loan'    THEN 1 ELSE 0 END) AS has_loan,
           MAX(CASE WHEN product = 'card'    THEN 1 ELSE 0 END) AS has_card
    FROM (
        SELECT cust_id, 'deposit' AS product FROM deposits WHERE status = 'active'
        UNION ALL
        SELECT cust_id, 'fund'    FROM holdings
        UNION ALL
        SELECT cust_id, 'loan'    FROM loans WHERE status IN ('active', 'overdue')
        UNION ALL
        SELECT cust_id, 'card'    FROM cards WHERE status = 'active'
    ) p
    GROUP BY cust_id
)
SELECT CONCAT(CONCAT(CAST(has_deposit AS VARCHAR(1)), CAST(has_fund AS VARCHAR(1))),
              CONCAT(CAST(has_loan AS VARCHAR(1)), CAST(has_card AS VARCHAR(1)))) AS product_combo,
       COUNT(*) AS cust_cnt,
       ROUND(COUNT(*) / NULLIF(SUM(COUNT(*)) OVER (), 0) * 100, 2) AS cust_share_pct,
       CASE WHEN has_deposit = 1 AND has_fund = 0 THEN 'cross_sell_fund'
            WHEN has_deposit = 1 AND has_card = 0 THEN 'cross_sell_card'
            WHEN has_loan = 1 AND has_deposit = 0 THEN 'cross_sell_deposit'
            ELSE 'none' END AS opportunity
FROM hold
GROUP BY CONCAT(CONCAT(CAST(has_deposit AS VARCHAR(1)), CAST(has_fund AS VARCHAR(1))),
                CONCAT(CAST(has_loan AS VARCHAR(1)), CAST(has_card AS VARCHAR(1)))),
         CASE WHEN has_deposit = 1 AND has_fund = 0 THEN 'cross_sell_fund'
              WHEN has_deposit = 1 AND has_card = 0 THEN 'cross_sell_card'
              WHEN has_loan = 1 AND has_deposit = 0 THEN 'cross_sell_deposit'
              ELSE 'none' END
ORDER BY cust_cnt DESC;

-- ------------------------------------------------------------------------------
-- [126] 偏好·交易渠道 | 金融 | 交易渠道偏好与渠道迁移趋势
-- ------------------------------------------------------------------------------
SELECT t.channel,
       TO_CHAR(t.txn_date, 'YYYY-MM') AS ym,
       COUNT(*)                    AS txn_cnt,
       ROUND(SUM(t.amount), 2)     AS total_amt,
       ROUND(AVG(t.amount), 2)     AS avg_amt,
       COUNT(DISTINCT a.cust_id)   AS cust_cnt,
       ROUND(SUM(t.amount) / NULLIF(SUM(SUM(t.amount)) OVER (PARTITION BY TO_CHAR(t.txn_date, 'YYYY-MM')), 0) * 100, 2) AS channel_share_pct,
       ROUND(SUM(t.amount) - LAG(SUM(t.amount)) OVER (PARTITION BY t.channel ORDER BY TO_CHAR(t.txn_date, 'YYYY-MM')), 2) AS mom_delta
FROM transactions t
JOIN accounts a ON t.account_id = a.account_id
WHERE t.txn_date >= (TRUNC(SYSDATE) - 180)
GROUP BY t.channel, TO_CHAR(t.txn_date, 'YYYY-MM')
ORDER BY ym DESC, txn_cnt DESC;

-- ------------------------------------------------------------------------------
-- [127] 异常·非营业时间 | 金融 | 非营业时间交易监控
-- ------------------------------------------------------------------------------
WITH t AS (
    SELECT t.txn_id, t.account_id, t.amount, t.txn_date, t.channel,
           a.cust_id,
           EXTRACT(HOUR FROM CAST(t.txn_date AS TIMESTAMP)) AS txn_hour,
           (TO_NUMBER(TO_CHAR(t.txn_date, 'D')) - 1)  AS txn_dow
    FROM transactions t
    JOIN accounts a ON t.account_id = a.account_id
    WHERE t.txn_date >= (TRUNC(SYSDATE) - 90)
)
SELECT cust_id,
       COUNT(*)                                                    AS total_txn,
       SUM(CASE WHEN txn_hour < 8 OR txn_hour >= 20 THEN 1 ELSE 0 END) AS offhour_cnt,
       SUM(CASE WHEN txn_dow IN (0, 6)              THEN 1 ELSE 0 END) AS weekend_cnt,
       SUM(CASE WHEN txn_hour >= 0 AND txn_hour < 6 THEN 1 ELSE 0 END) AS midnight_cnt,
       ROUND(SUM(CASE WHEN txn_hour < 8 OR txn_hour >= 20 THEN amount ELSE 0 END), 2) AS offhour_amt,
       ROUND(SUM(CASE WHEN txn_hour < 8 OR txn_hour >= 20 THEN 1 ELSE 0 END)
             / NULLIF(COUNT(*), 0) * 100, 2) AS offhour_pct,
       CASE WHEN SUM(CASE WHEN txn_hour >= 0 AND txn_hour < 6 THEN 1 ELSE 0 END) >= 5 THEN 'high_risk'
            WHEN SUM(CASE WHEN txn_hour < 8 OR txn_hour >= 20 THEN 1 ELSE 0 END)
                 / NULLIF(COUNT(*), 0) > 0.4 THEN 'medium_risk'
            ELSE 'normal' END AS time_risk_flag
FROM t
GROUP BY cust_id
HAVING SUM(CASE WHEN txn_hour < 8 OR txn_hour >= 20 THEN 1 ELSE 0 END) >= 3
ORDER BY offhour_pct DESC
FETCH FIRST 300 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [128] 异地·交易监控 | 金融 | 异地/非常用地交易识别
-- ------------------------------------------------------------------------------
WITH home AS (
    SELECT c.cust_id, c.city AS home_city
    FROM fin_customers c
),
t AS (
    SELECT a.cust_id, t.txn_id, t.amount, t.txn_date, t.city AS txn_city,
           h.home_city
    FROM transactions t
    JOIN accounts a ON t.account_id = a.account_id
    JOIN home h     ON a.cust_id = h.cust_id
    WHERE t.txn_date >= (TRUNC(SYSDATE) - 90)
)
SELECT cust_id,
       COUNT(*)                                                       AS txn_cnt,
       COUNT(DISTINCT txn_city)                                       AS city_cnt,
       SUM(CASE WHEN txn_city <> home_city THEN 1 ELSE 0 END)         AS remote_cnt,
       ROUND(SUM(CASE WHEN txn_city <> home_city THEN amount ELSE 0 END), 2) AS remote_amt,
       ROUND(SUM(CASE WHEN txn_city <> home_city THEN 1 ELSE 0 END)
             / NULLIF(COUNT(*), 0) * 100, 2)                          AS remote_pct,
       CASE WHEN COUNT(DISTINCT txn_city) >= 10 THEN 'multi_city_risk'
            WHEN SUM(CASE WHEN txn_city <> home_city THEN 1 ELSE 0 END)
                 / NULLIF(COUNT(*), 0) > 0.5 THEN 'frequent_remote'
            ELSE 'normal' END AS geo_risk_flag
FROM t
GROUP BY cust_id
HAVING SUM(CASE WHEN txn_city <> home_city THEN 1 ELSE 0 END) >= 3
ORDER BY remote_pct DESC
FETCH FIRST 300 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [129] 关联·账户网络 | 金融 | 同客户多账户资金往来与关联交易
-- ------------------------------------------------------------------------------
WITH cust_acct AS (
    SELECT cust_id, account_id FROM accounts
),
pairs AS (
    SELECT a1.cust_id AS cust_a, a2.cust_id AS cust_b,
           COUNT(*) AS txn_cnt, SUM(t.amount) AS total_amt
    FROM transactions t
    JOIN cust_acct a1 ON t.account_id = a1.account_id
    JOIN cust_acct a2 ON t.counterparty = CAST(a2.account_id AS VARCHAR(20))
    WHERE a1.cust_id < a2.cust_id
      AND t.txn_date >= (TRUNC(SYSDATE) - 180)
    GROUP BY a1.cust_id, a2.cust_id
)
SELECT ca.cust_name AS cust_a_name, cb.cust_name AS cust_b_name,
       p.txn_cnt, ROUND(p.total_amt, 2) AS total_amt,
       ROUND(AVG(p.total_amt) OVER (), 2) AS avg_pair_amt,
       RANK() OVER (ORDER BY p.total_amt DESC) AS pair_rank,
       CASE WHEN p.total_amt >= 1000000 THEN 'large_link'
            WHEN p.txn_cnt >= 50 THEN 'frequent_link'
            ELSE 'normal' END AS link_flag
FROM pairs p
JOIN fin_customers ca ON p.cust_a = ca.cust_id
JOIN fin_customers cb ON p.cust_b = cb.cust_id
ORDER BY p.total_amt DESC
FETCH FIRST 200 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [130] 反洗钱·拆分交易 | 金融 | 拆分交易（化整为零）规避监测识别
-- ------------------------------------------------------------------------------
WITH day_txn AS (
    SELECT account_id, CAST(txn_date AS DATE) AS dt,
           COUNT(*)                                                  AS txn_cnt,
           SUM(amount)                                              AS day_total,
           AVG(amount)                                              AS avg_amount,
           MAX(amount)                                              AS max_amount,
           SUM(CASE WHEN amount BETWEEN 8000 AND 10000 THEN 1 ELSE 0 END) AS near_threshold_cnt
    FROM transactions
    WHERE txn_type = 'transfer_out'
      AND txn_date >= (TRUNC(SYSDATE) - 90)
    GROUP BY account_id, CAST(txn_date AS DATE)
)
SELECT account_id, dt, txn_cnt,
       ROUND(day_total, 2)  AS day_total,
       ROUND(avg_amount, 2) AS avg_amount,
       near_threshold_cnt,
       ROUND(near_threshold_cnt / NULLIF(txn_cnt, 0) * 100, 2) AS near_threshold_pct,
       CASE WHEN txn_cnt >= 5 AND day_total >= 50000
                 AND near_threshold_cnt / NULLIF(txn_cnt, 0) >= 0.6 THEN 'structuring_suspect'
            WHEN txn_cnt >= 10 AND day_total >= 100000 THEN 'high_volume'
            ELSE 'normal' END AS aml_flag
FROM day_txn
WHERE txn_cnt >= 3
ORDER BY near_threshold_pct DESC, day_total DESC
FETCH FIRST 300 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [131] 循环·资金链路 | 金融 | 循环转账链路检测（A→B→C→A）
-- ------------------------------------------------------------------------------
WITH edges AS (
    SELECT t.account_id AS from_acct, a2.account_id AS to_acct,
           COUNT(*) AS txn_cnt, SUM(t.amount) AS total_amt
    FROM transactions t
    JOIN accounts a2 ON t.counterparty = CAST(a2.account_id AS VARCHAR(20))
    WHERE t.txn_type = 'transfer_out'
      AND t.txn_date >= (TRUNC(SYSDATE) - 180)
    GROUP BY t.account_id, a2.account_id
),
cycle2 AS (
    SELECT e1.from_acct AS a, e1.to_acct AS b, e2.to_acct AS c,
           LEAST(e1.total_amt, e2.total_amt) AS min_amt
    FROM edges e1
    JOIN edges e2 ON e1.to_acct = e2.from_acct AND e2.to_acct = e1.from_acct
)
SELECT a AS account_a, b AS account_b,
       COUNT(*) AS round_trip_pairs,
       ROUND(SUM(min_amt), 2) AS round_trip_amt,
       CASE WHEN SUM(min_amt) >= 500000 THEN 'high_risk_cycle'
            WHEN COUNT(*) >= 3 THEN 'repeated_cycle'
            ELSE 'normal' END AS cycle_flag
FROM cycle2
GROUP BY a, b
HAVING SUM(min_amt) >= 100000
ORDER BY round_trip_amt DESC
FETCH FIRST 200 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [132] 分布·风险事件 | 金融 | 风险事件类型分布与等级统计
-- ------------------------------------------------------------------------------
SELECT event_type, risk_level,
       COUNT(*)                                          AS event_cnt,
       COUNT(DISTINCT cust_id)                           AS affected_cust,
       ROUND(SUM(amount), 2)                             AS total_amt,
       ROUND(AVG(amount), 2)                             AS avg_amt,
       MIN(event_time)                                   AS first_event,
       MAX(event_time)                                   AS last_event,
       ROUND(COUNT(*) / NULLIF(SUM(COUNT(*)) OVER (PARTITION BY event_type), 0) * 100, 2) AS level_share_pct,
       RANK() OVER (PARTITION BY event_type ORDER BY COUNT(*) DESC) AS level_rank
FROM risk_events
WHERE event_time >= (TRUNC(SYSDATE) - 180)
GROUP BY event_type, risk_level
ORDER BY event_type, event_cnt DESC;

-- ------------------------------------------------------------------------------
-- [133] 画像·风险客户 | 金融 | 高风险客户画像与综合风险评分
-- ------------------------------------------------------------------------------
WITH evt AS (
    SELECT cust_id,
           COUNT(*)                                                    AS event_cnt,
           SUM(CASE WHEN risk_level = 'high'   THEN 1 ELSE 0 END)      AS high_cnt,
           SUM(CASE WHEN risk_level = 'medium' THEN 1 ELSE 0 END)      AS medium_cnt,
           SUM(amount)                                                 AS event_amount
    FROM risk_events
    WHERE event_time >= (TRUNC(SYSDATE) - 365)
    GROUP BY cust_id
),
score AS (
    SELECT cust_id, AVG(score) AS avg_score, MAX(score) AS max_score,
           MIN(score) AS min_score
    FROM credit_scores
    GROUP BY cust_id
),
overdue AS (
    SELECT l.cust_id,
           COUNT(*)                                            AS overdue_cnt,
           SUM(CASE WHEN r.overdue_days > 90 THEN 1 ELSE 0 END) AS severe_cnt
    FROM loans l
    JOIN repayments r ON l.loan_id = r.loan_id
    WHERE r.overdue_days > 0
    GROUP BY l.cust_id
)
SELECT c.cust_id, c.cust_name, c.risk_level AS declared_level,
       COALESCE(e.event_cnt, 0)       AS event_cnt,
       COALESCE(e.high_cnt, 0)        AS high_risk_events,
       ROUND(s.avg_score, 1)          AS avg_credit_score,
       COALESCE(o.overdue_cnt, 0)     AS overdue_cnt,
       COALESCE(o.severe_cnt, 0)      AS severe_overdue_cnt,
       (COALESCE(e.high_cnt, 0) * 10 + COALESCE(o.severe_cnt, 0) * 15
        + COALESCE(o.overdue_cnt, 0) * 5
        + CASE WHEN s.avg_score < 600 THEN 20 ELSE 0 END) AS composite_risk_score,
       CASE WHEN (COALESCE(e.high_cnt, 0) * 10 + COALESCE(o.severe_cnt, 0) * 15
                  + COALESCE(o.overdue_cnt, 0) * 5
                  + CASE WHEN s.avg_score < 600 THEN 20 ELSE 0 END) >= 50 THEN 'high'
            WHEN (COALESCE(e.high_cnt, 0) * 10 + COALESCE(o.severe_cnt, 0) * 15
                  + COALESCE(o.overdue_cnt, 0) * 5
                  + CASE WHEN s.avg_score < 600 THEN 20 ELSE 0 END) >= 20 THEN 'medium'
            ELSE 'low' END AS computed_risk_level
FROM fin_customers c
LEFT JOIN evt     e ON c.cust_id = e.cust_id
LEFT JOIN score   s ON c.cust_id = s.cust_id
LEFT JOIN overdue o ON c.cust_id = o.cust_id
ORDER BY composite_risk_score DESC
FETCH FIRST 500 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [134] 分布·信用评分 | 金融 | 信用评分分布与风险分层
-- ------------------------------------------------------------------------------
SELECT CASE WHEN score >= 800 THEN 'excellent_800+'
            WHEN score >= 740 THEN 'very_good_740_800'
            WHEN score >= 670 THEN 'good_670_740'
            WHEN score >= 580 THEN 'fair_580_670'
            ELSE 'poor_below_580' END AS score_band,
       COUNT(*)                   AS cust_cnt,
       ROUND(AVG(score), 1)       AS avg_score,
       MIN(score)                 AS min_score,
       MAX(score)                 AS max_score,
       ROUND(COUNT(*) / NULLIF(SUM(COUNT(*)) OVER (), 0) * 100, 2) AS cust_share_pct
FROM (
    SELECT cust_id, AVG(score) AS score
    FROM credit_scores
    WHERE score_date >= (TRUNC(SYSDATE) - 90)
    GROUP BY cust_id
) s
GROUP BY CASE WHEN score >= 800 THEN 'excellent_800+'
              WHEN score >= 740 THEN 'very_good_740_800'
              WHEN score >= 670 THEN 'good_670_740'
              WHEN score >= 580 THEN 'fair_580_670'
              ELSE 'poor_below_580' END
ORDER BY avg_score DESC;

-- ------------------------------------------------------------------------------
-- [135] 趋势·评分变化 | 金融 | 客户信用评分变化趋势与恶化预警
-- ------------------------------------------------------------------------------
WITH cs AS (
    SELECT cust_id, score_date, score,
           LAG(score) OVER (PARTITION BY cust_id ORDER BY score_date) AS prev_score,
           AVG(score) OVER (PARTITION BY cust_id)                     AS avg_score,
           ROW_NUMBER() OVER (PARTITION BY cust_id ORDER BY score_date DESC) AS rn
    FROM credit_scores
)
SELECT cs.cust_id, c.cust_name,
       score_date, score, prev_score,
       score - prev_score                        AS score_delta,
       ROUND(score - avg_score, 1)               AS vs_avg,
       ROUND((score - prev_score) / NULLIF(prev_score, 0) * 100, 2) AS change_pct,
       CASE WHEN score - prev_score <= -50 THEN 'severe_decline'
            WHEN score - prev_score <= -20 THEN 'declining'
            WHEN score - prev_score >= 20  THEN 'improving'
            ELSE 'stable' END AS trend_flag
FROM cs
JOIN fin_customers c ON cs.cust_id = c.cust_id
WHERE cs.prev_score IS NOT NULL
  AND cs.rn = 1
  AND cs.score - cs.prev_score <= -20
ORDER BY score_delta ASC
FETCH FIRST 300 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [136] 关联·评分违约 | 金融 | 信用评分与贷款违约率关联分析
-- ------------------------------------------------------------------------------
WITH cust_score AS (
    SELECT cust_id, AVG(score) AS avg_score
    FROM credit_scores
    GROUP BY cust_id
),
loan_stat AS (
    SELECT l.cust_id,
           COUNT(*)                                                    AS loan_cnt,
           SUM(CASE WHEN l.status = 'overdue' OR l.status = 'default' THEN 1 ELSE 0 END) AS bad_cnt,
           SUM(l.loan_amount)                                          AS total_loan_amt
    FROM loans l
    GROUP BY l.cust_id
)
SELECT CASE WHEN s.avg_score >= 800 THEN 'excellent_800+'
            WHEN s.avg_score >= 740 THEN 'very_good_740_800'
            WHEN s.avg_score >= 670 THEN 'good_670_740'
            WHEN s.avg_score >= 580 THEN 'fair_580_670'
            ELSE 'poor_below_580' END AS score_band,
       COUNT(*)                                                     AS cust_cnt,
       SUM(ls.loan_cnt)                                             AS loan_cnt,
       SUM(ls.bad_cnt)                                              AS bad_cnt,
       ROUND(SUM(ls.bad_cnt) / NULLIF(SUM(ls.loan_cnt), 0) * 100, 2) AS default_rate_pct,
       ROUND(SUM(ls.total_loan_amt), 2)                             AS total_loan_amt,
       ROUND(SUM(CASE WHEN ls.bad_cnt > 0 THEN ls.total_loan_amt ELSE 0 END)
             / NULLIF(SUM(ls.total_loan_amt), 0) * 100, 2)          AS bad_loan_amt_pct
FROM cust_score s
JOIN loan_stat ls ON s.cust_id = ls.cust_id
GROUP BY CASE WHEN s.avg_score >= 800 THEN 'excellent_800+'
              WHEN s.avg_score >= 740 THEN 'very_good_740_800'
              WHEN s.avg_score >= 670 THEN 'good_670_740'
              WHEN s.avg_score >= 580 THEN 'fair_580_670'
              ELSE 'poor_below_580' END
ORDER BY default_rate_pct DESC;

-- ------------------------------------------------------------------------------
-- [137] 监控·高风险客户 | 金融 | 高风险客户交易行为实时监控
-- ------------------------------------------------------------------------------
WITH risky AS (
    SELECT cust_id
    FROM fin_customers
    WHERE risk_level = 'high'
),
txn AS (
    SELECT r.cust_id, t.txn_id, t.amount, t.txn_date, t.txn_type, t.channel, t.city,
           COUNT(*) OVER (PARTITION BY r.cust_id ORDER BY t.txn_date
                          ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS cum_txn
    FROM transactions t
    JOIN accounts   a ON t.account_id = a.account_id
    JOIN risky      r ON a.cust_id = r.cust_id
    WHERE t.txn_date >= (TRUNC(SYSDATE) - 30)
)
SELECT cust_id,
       COUNT(*)                        AS txn_cnt,
       ROUND(SUM(amount), 2)           AS total_amt,
       ROUND(AVG(amount), 2)           AS avg_amt,
       MAX(amount)                     AS max_amt,
       COUNT(DISTINCT city)            AS city_cnt,
       COUNT(DISTINCT channel)         AS channel_cnt,
       MAX(cum_txn)                    AS peak_cum_txn,
       CASE WHEN SUM(amount) >= 1000000 THEN 'enhanced_dd'
            WHEN COUNT(*) >= 100 THEN 'transaction_monitoring'
            ELSE 'routine' END AS monitoring_action
FROM txn
GROUP BY cust_id
ORDER BY total_amt DESC
FETCH FIRST 300 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [138] 合规·可疑报告 | 金融 | 可疑交易报告（STR）候选名单生成
-- ------------------------------------------------------------------------------
WITH suspicious AS (
    SELECT t.account_id, a.cust_id, t.txn_id, t.amount, t.txn_date, t.txn_type, t.city,
           CASE WHEN t.amount >= 200000 THEN 3
                WHEN t.amount >= 50000  THEN 2
                ELSE 1 END AS amount_score,
           CASE WHEN EXTRACT(HOUR FROM CAST(t.txn_date AS TIMESTAMP)) >= 20 OR EXTRACT(HOUR FROM CAST(t.txn_date AS TIMESTAMP)) < 8 THEN 2 ELSE 0 END AS time_score
    FROM transactions t
    JOIN accounts a ON t.account_id = a.account_id
    WHERE t.txn_date >= (TRUNC(SYSDATE) - 30)
),
scored AS (
    SELECT cust_id, txn_id, amount, txn_date, txn_type, city,
           amount_score + time_score AS risk_score
    FROM suspicious
)
SELECT s.cust_id, c.cust_name, c.risk_level,
       COUNT(*)                       AS suspicious_txn_cnt,
       ROUND(SUM(amount), 2)          AS total_amt,
       SUM(risk_score)                AS total_risk_score,
       MAX(txn_date)                  AS last_txn_date,
       LISTAGG(CAST(txn_id AS VARCHAR(20)), ',') WITHIN GROUP (ORDER BY txn_id) AS txn_list,
       CASE WHEN SUM(risk_score) >= 20 THEN 'file_str'
            WHEN SUM(risk_score) >= 10 THEN 'manual_review'
            ELSE 'monitor' END AS reporting_action
FROM scored s
JOIN fin_customers c ON s.cust_id = c.cust_id
GROUP BY s.cust_id, c.cust_name, c.risk_level
HAVING SUM(risk_score) >= 10
ORDER BY total_risk_score DESC
FETCH FIRST 200 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [139] 迁移·风险等级 | 金融 | 客户风险等级迁移矩阵
-- ------------------------------------------------------------------------------
WITH cur AS (
    SELECT cust_id, risk_level AS current_level FROM fin_customers
),
evt_hist AS (
    SELECT cust_id,
           TO_CHAR(event_time, 'YYYY-MM') AS ym,
           MAX(CASE WHEN risk_level = 'high'   THEN 3
                    WHEN risk_level = 'medium' THEN 2
                    ELSE 1 END) AS level_num
    FROM risk_events
    WHERE event_time >= (TRUNC(SYSDATE) - 180)
    GROUP BY cust_id, TO_CHAR(event_time, 'YYYY-MM')
),
trend AS (
    SELECT cust_id,
           MIN(level_num) AS min_level,
           MAX(level_num) AS max_level,
           COUNT(*)       AS active_months
    FROM evt_hist GROUP BY cust_id
)
SELECT cur.current_level,
       CASE WHEN t.max_level = 3 THEN 'touched_high'
            WHEN t.max_level = 2 THEN 'touched_medium'
            WHEN t.cust_id IS NULL THEN 'no_event'
            ELSE 'low_only' END AS historical_peak,
       COUNT(*)                          AS cust_cnt,
       ROUND(AVG(t.active_months), 2)    AS avg_active_months,
       ROUND(COUNT(*) / NULLIF(SUM(COUNT(*)) OVER (PARTITION BY cur.current_level), 0) * 100, 2) AS share_pct
FROM cur
LEFT JOIN trend t ON cur.cust_id = t.cust_id
GROUP BY cur.current_level,
         CASE WHEN t.max_level = 3 THEN 'touched_high'
              WHEN t.max_level = 2 THEN 'touched_medium'
              WHEN t.cust_id IS NULL THEN 'no_event'
              ELSE 'low_only' END
ORDER BY cur.current_level, cust_cnt DESC;

-- ------------------------------------------------------------------------------
-- [140] 分位数·阈值 | 金融 | 交易金额分位数与监测阈值建议
-- ------------------------------------------------------------------------------
WITH amt AS (
    SELECT txn_type, amount,
           NTILE(100) OVER (PARTITION BY txn_type ORDER BY amount) AS pctile
    FROM transactions
    WHERE txn_date >= (TRUNC(SYSDATE) - 180)
)
SELECT txn_type,
       MAX(CASE WHEN pctile = 50  THEN amount END) AS p50,
       MAX(CASE WHEN pctile = 90  THEN amount END) AS p90,
       MAX(CASE WHEN pctile = 95  THEN amount END) AS p95,
       MAX(CASE WHEN pctile = 99  THEN amount END) AS p99,
       MAX(amount)                                 AS pmax,
       ROUND(AVG(amount), 2)                       AS pavg,
       ROUND(MAX(CASE WHEN pctile = 99 THEN amount END)
             / NULLIF(MAX(CASE WHEN pctile = 50 THEN amount END), 0), 2) AS p99_p50_ratio
FROM amt
GROUP BY txn_type
ORDER BY p99 DESC;

-- ------------------------------------------------------------------------------
-- [141] 转化·开户激活 | 金融 | 账户开户到首笔交易转化分析
-- ------------------------------------------------------------------------------
WITH first_txn AS (
    SELECT account_id, MIN(txn_date) AS first_dt
    FROM transactions GROUP BY account_id
)
SELECT a.account_type,
       TO_CHAR(a.open_date, 'YYYY-MM') AS open_ym,
       COUNT(*)                                                     AS opened_cnt,
       COUNT(ft.account_id)                                         AS activated_cnt,
       ROUND(COUNT(ft.account_id) / NULLIF(COUNT(*), 0) * 100, 2)   AS activation_pct,
       ROUND(AVG((ft.first_dt - a.open_date)), 2)          AS avg_activation_days,
       SUM(CASE WHEN (ft.first_dt - a.open_date) <= 7 THEN 1 ELSE 0 END) AS within_7d,
       SUM(CASE WHEN (ft.first_dt - a.open_date) <= 30 THEN 1 ELSE 0 END) AS within_30d
FROM accounts a
LEFT JOIN first_txn ft ON a.account_id = ft.account_id
WHERE a.open_date >= (TRUNC(SYSDATE) - 365)
GROUP BY a.account_type, TO_CHAR(a.open_date, 'YYYY-MM')
ORDER BY open_ym DESC, opened_cnt DESC;

-- ------------------------------------------------------------------------------
-- [142] 活跃·MAU分析 | 金融 | 客户月度活跃度与留存（MAU/留存率）
-- ------------------------------------------------------------------------------
WITH act AS (
    SELECT DISTINCT a.cust_id, TO_CHAR(t.txn_date, 'YYYY-MM') AS ym
    FROM transactions t
    JOIN accounts a ON t.account_id = a.account_id
    WHERE t.txn_date >= (TRUNC(SYSDATE) - 365)
),
seq AS (
    SELECT cust_id, ym,
           LEAD(ym) OVER (PARTITION BY cust_id ORDER BY ym) AS next_ym
    FROM act
),
cont AS (
    SELECT ym,
           COUNT(*) AS active_cust,
           SUM(CASE WHEN next_ym IS NOT NULL THEN 1 ELSE 0 END) AS retained_cnt
    FROM seq GROUP BY ym
)
SELECT ym, active_cust,
       LAG(active_cust) OVER (ORDER BY ym) AS prev_active,
       retained_cnt,
       ROUND(retained_cnt / NULLIF(active_cust, 0) * 100, 2) AS retention_pct,
       ROUND((active_cust - LAG(active_cust) OVER (ORDER BY ym))
             / NULLIF(LAG(active_cust) OVER (ORDER BY ym), 0) * 100, 2) AS mau_growth_pct
FROM cont
ORDER BY ym;

-- ------------------------------------------------------------------------------
-- [143] LTV·客户价值 | 金融 | 客户生命周期价值（金融 LTV）评估
-- ------------------------------------------------------------------------------
WITH rev AS (
    SELECT l.cust_id,
           SUM(l.loan_amount * l.interest_rate / 100) AS interest_income,
           COUNT(*) AS loan_cnt
    FROM loans l
    GROUP BY l.cust_id
),
dep_profit AS (
    SELECT d.cust_id,
           SUM(d.amount * (0.03 - d.rate)) AS deposit_spread_income
    FROM deposits d
    GROUP BY d.cust_id
),
tenure AS (
    SELECT cust_id,
           (TRUNC(SYSDATE) - MIN(open_date)) / 365.0 AS tenure_years
    FROM accounts GROUP BY cust_id
)
SELECT c.cust_id, c.cust_name,
       ROUND(COALESCE(r.interest_income, 0), 2)          AS interest_income,
       ROUND(COALESCE(dp.deposit_spread_income, 0), 2)   AS deposit_income,
       ROUND(COALESCE(r.interest_income, 0) + COALESCE(dp.deposit_spread_income, 0), 2) AS total_income,
       ROUND(t.tenure_years, 2)                          AS tenure_years,
       ROUND((COALESCE(r.interest_income, 0) + COALESCE(dp.deposit_spread_income, 0))
             / NULLIF(t.tenure_years, 0), 2)             AS annual_value,
       ROUND((COALESCE(r.interest_income, 0) + COALESCE(dp.deposit_spread_income, 0))
             / NULLIF(t.tenure_years, 0) * 5, 2)         AS projected_ltv_5y,
       RANK() OVER (ORDER BY (COALESCE(r.interest_income, 0) + COALESCE(dp.deposit_spread_income, 0))
             / NULLIF(t.tenure_years, 0) DESC)           AS ltv_rank
FROM fin_customers c
LEFT JOIN rev        r  ON c.cust_id = r.cust_id
LEFT JOIN dep_profit dp ON c.cust_id = dp.cust_id
LEFT JOIN tenure     t  ON c.cust_id = t.cust_id
ORDER BY annual_value DESC
FETCH FIRST 500 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [144] 流失·预警模型 | 金融 | 客户流失预警（活跃度衰减 + 资产流出）
-- ------------------------------------------------------------------------------
WITH act AS (
    SELECT a.cust_id,
           SUM(CASE WHEN t.txn_date >= (TRUNC(SYSDATE) - 90)  THEN 1 ELSE 0 END) AS cnt_90d,
           SUM(CASE WHEN t.txn_date >= (TRUNC(SYSDATE) - 180)
                     AND t.txn_date <  (TRUNC(SYSDATE) - 90)  THEN 1 ELSE 0 END) AS cnt_prev_90d,
           MAX(t.txn_date) AS last_txn
    FROM transactions t
    JOIN accounts a ON t.account_id = a.account_id
    GROUP BY a.cust_id
),
bal AS (
    SELECT cust_id, SUM(balance) AS cur_balance
    FROM accounts WHERE status = 'active' GROUP BY cust_id
)
SELECT c.cust_id, c.cust_name, c.cust_type,
       a.cnt_90d, a.cnt_prev_90d,
       (TRUNC(SYSDATE) - a.last_txn) AS idle_days,
       ROUND(b.cur_balance, 2)           AS cur_balance,
       ROUND((a.cnt_90d - a.cnt_prev_90d) / NULLIF(a.cnt_prev_90d, 0) * 100, 2) AS activity_change_pct,
       CASE WHEN (TRUNC(SYSDATE) - a.last_txn) > 180 THEN 'churned'
            WHEN (TRUNC(SYSDATE) - a.last_txn) > 90
                 AND a.cnt_90d < a.cnt_prev_90d THEN 'high_churn_risk'
            WHEN a.cnt_90d < a.cnt_prev_90d * 0.5 THEN 'declining'
            ELSE 'stable' END AS churn_flag
FROM act a
JOIN fin_customers c ON a.cust_id = c.cust_id
LEFT JOIN bal b      ON a.cust_id = b.cust_id
WHERE (TRUNC(SYSDATE) - a.last_txn) > 60
ORDER BY cur_balance DESC
FETCH FIRST 500 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [145] 流失·资产流出 | 金融 | 客户资产净流出监控与挽留优先级
-- ------------------------------------------------------------------------------
WITH flow AS (
    SELECT a.cust_id,
           SUM(CASE WHEN t.txn_type = 'transfer_in'  THEN t.amount ELSE 0 END) AS inflow,
           SUM(CASE WHEN t.txn_type = 'transfer_out' THEN t.amount ELSE 0 END) AS outflow
    FROM transactions t
    JOIN accounts a ON t.account_id = a.account_id
    WHERE t.txn_date >= (TRUNC(SYSDATE) - 90)
    GROUP BY a.cust_id
)
SELECT c.cust_id, c.cust_name, c.risk_level,
       ROUND(f.inflow, 2)                      AS inflow_90d,
       ROUND(f.outflow, 2)                     AS outflow_90d,
       ROUND(f.inflow - f.outflow, 2)          AS net_flow,
       ROUND((f.inflow - f.outflow)
             / NULLIF(GREATEST(f.inflow, f.outflow), 0) * 100, 2) AS net_flow_pct,
       CASE WHEN f.outflow > f.inflow * 3 AND f.outflow >= 100000 THEN 'urgent_retention'
            WHEN f.outflow > f.inflow          THEN 'net_outflow'
            ELSE 'net_inflow' END AS retention_priority
FROM flow f
JOIN fin_customers c ON f.cust_id = c.cust_id
WHERE f.outflow > f.inflow
ORDER BY net_flow ASC
FETCH FIRST 300 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [146] 排名·分支行规模 | 金融 | 分支行存款规模排名与市场份额
-- ------------------------------------------------------------------------------
WITH dep AS (
    SELECT branch_id, COUNT(*) AS dep_cnt, SUM(amount) AS dep_amt, AVG(rate) AS avg_rate
    FROM deposits WHERE status = 'active' GROUP BY branch_id
)
SELECT b.branch_name, b.city, b.region,
       d.dep_cnt,
       ROUND(d.dep_amt, 2)                                        AS dep_amt,
       ROUND(d.avg_rate * 100, 3)                                 AS avg_rate_pct,
       ROUND(d.dep_amt / NULLIF(SUM(d.dep_amt) OVER (), 0) * 100, 2)      AS market_share_pct,
       ROUND(d.dep_amt / NULLIF(SUM(d.dep_amt) OVER (PARTITION BY b.region), 0) * 100, 2) AS region_share_pct,
       RANK() OVER (ORDER BY d.dep_amt DESC)                      AS rank_all,
       RANK() OVER (PARTITION BY b.region ORDER BY d.dep_amt DESC) AS rank_in_region
FROM dep d
JOIN branches b ON d.branch_id = b.branch_id
ORDER BY dep_amt DESC;

-- ------------------------------------------------------------------------------
-- [147] 质量·贷款资产 | 金融 | 分支行贷款质量与不良率排名
-- ------------------------------------------------------------------------------
WITH ln AS (
    SELECT branch_id,
           COUNT(*)                                                        AS loan_cnt,
           SUM(loan_amount)                                                AS loan_amt,
           SUM(CASE WHEN status = 'overdue' OR status = 'default' THEN 1 ELSE 0 END) AS bad_cnt,
           SUM(CASE WHEN status = 'overdue' OR status = 'default' THEN loan_amount ELSE 0 END) AS bad_amt
    FROM loans GROUP BY branch_id
)
SELECT b.branch_name, b.region,
       ln.loan_cnt,
       ROUND(ln.loan_amt, 2)                                          AS loan_amt,
       ln.bad_cnt,
       ROUND(ln.bad_amt, 2)                                           AS bad_amt,
       ROUND(ln.bad_cnt / NULLIF(ln.loan_cnt, 0) * 100, 2)            AS bad_cnt_rate_pct,
       ROUND(ln.bad_amt / NULLIF(ln.loan_amt, 0) * 100, 2)            AS npl_ratio_pct,
       RANK() OVER (ORDER BY ln.bad_amt / NULLIF(ln.loan_amt, 0) DESC) AS npl_rank,
       CASE WHEN ln.bad_amt / NULLIF(ln.loan_amt, 0) > 0.05 THEN 'high_npl'
            WHEN ln.bad_amt / NULLIF(ln.loan_amt, 0) > 0.02 THEN 'watch'
            ELSE 'healthy' END AS asset_quality
FROM ln
JOIN branches b ON ln.branch_id = b.branch_id
ORDER BY npl_ratio_pct DESC;

-- ------------------------------------------------------------------------------
-- [148] 盈利·分支行 | 金融 | 分支行盈利贡献与成本收入比
-- ------------------------------------------------------------------------------
WITH r AS (
    SELECT branch_id,
           SUM(revenue)                                   AS total_revenue,
           SUM(cost)                                      AS total_cost,
           SUM(profit)                                    AS total_profit,
           COUNT(DISTINCT period)                         AS period_cnt
    FROM fin_reports
    WHERE period >= TO_CHAR((TRUNC(SYSDATE) - 365), 'YYYY-MM')
    GROUP BY branch_id
)
SELECT b.branch_name, b.city, b.region,
       ROUND(r.total_revenue, 2) AS revenue,
       ROUND(r.total_cost, 2)    AS cost,
       ROUND(r.total_profit, 2)  AS profit,
       ROUND(r.total_cost / NULLIF(r.total_revenue, 0) * 100, 2) AS cost_income_ratio_pct,
       ROUND(r.total_profit / NULLIF(r.total_revenue, 0) * 100, 2) AS profit_margin_pct,
       ROUND(r.total_profit / NULLIF(r.period_cnt, 0), 2)         AS avg_period_profit,
       RANK() OVER (ORDER BY r.total_profit DESC)                 AS profit_rank,
       ROUND(r.total_profit / NULLIF(SUM(r.total_profit) OVER (), 0) * 100, 2) AS profit_contribution_pct
FROM r
JOIN branches b ON r.branch_id = b.branch_id
ORDER BY profit DESC;

-- ------------------------------------------------------------------------------
-- [149] 趋势·财务指标 | 金融 | 分支行财务指标同比与环比趋势
-- ------------------------------------------------------------------------------
WITH p AS (
    SELECT branch_id, period, revenue, cost, profit, deposit_amt, loan_amt
    FROM fin_reports
)
SELECT b.branch_name, p.period,
       ROUND(p.revenue, 2) AS revenue,
       ROUND(p.profit, 2)  AS profit,
       ROUND(p.revenue - LAG(p.revenue) OVER (PARTITION BY b.branch_id ORDER BY p.period), 2) AS revenue_mom,
       ROUND((p.revenue - LAG(p.revenue) OVER (PARTITION BY b.branch_id ORDER BY p.period))
             / NULLIF(LAG(p.revenue) OVER (PARTITION BY b.branch_id ORDER BY p.period), 0) * 100, 2) AS revenue_mom_pct,
       ROUND(SUM(p.revenue) OVER (PARTITION BY b.branch_id ORDER BY p.period
             ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW), 2) AS cum_revenue,
       ROUND(p.deposit_amt / NULLIF(p.loan_amt, 0), 2) AS deposit_loan_ratio
FROM p
JOIN branches b ON p.branch_id = b.branch_id
ORDER BY b.branch_name, p.period;

-- ------------------------------------------------------------------------------
-- [150] 息差·资金成本 | 金融 | 净息差（NIM）与资金成本分析
-- ------------------------------------------------------------------------------
WITH dep_cost AS (
    SELECT branch_id,
           SUM(amount)                AS dep_amt,
           SUM(amount * rate)         AS interest_cost
    FROM deposits WHERE status = 'active' GROUP BY branch_id
),
loan_inc AS (
    SELECT branch_id,
           SUM(loan_amount)                 AS loan_amt,
           SUM(loan_amount * interest_rate / 100) AS interest_income
    FROM loans WHERE status IN ('active', 'overdue') GROUP BY branch_id
)
SELECT b.branch_name, b.region,
       ROUND(dc.dep_amt, 2)                                              AS deposit_amt,
       ROUND(li.loan_amt, 2)                                             AS loan_amt,
       ROUND(dc.interest_cost, 2)                                        AS interest_cost,
       ROUND(li.interest_income, 2)                                      AS interest_income,
       ROUND((li.interest_income - dc.interest_cost), 2)                 AS net_interest_income,
       ROUND(dc.interest_cost / NULLIF(dc.dep_amt, 0) * 100, 3)          AS funding_cost_pct,
       ROUND(li.interest_income / NULLIF(li.loan_amt, 0) * 100, 3)       AS lending_yield_pct,
       ROUND((li.interest_income / NULLIF(li.loan_amt, 0)
              - dc.interest_cost / NULLIF(dc.dep_amt, 0)) * 100, 3)      AS nim_pct
FROM branches b
LEFT JOIN dep_cost dc ON b.branch_id = dc.branch_id
LEFT JOIN loan_inc li ON b.branch_id = li.branch_id
ORDER BY nim_pct DESC;

-- ------------------------------------------------------------------------------
-- [151] 消费·银行卡 | 金融 | 银行卡消费行为与额度使用分析
-- ------------------------------------------------------------------------------
WITH card_stat AS (
    SELECT t.card_id,
           COUNT(*)                                                    AS txn_cnt,
           SUM(t.amount)                                               AS total_amt,
           AVG(t.amount)                                               AS avg_amt,
           MAX(t.amount)                                               AS max_amt,
           SUM(CASE WHEN t.is_overseas = 1 THEN 1 ELSE 0 END)          AS overseas_cnt,
           SUM(CASE WHEN t.is_overseas = 1 THEN t.amount ELSE 0 END)   AS overseas_amt,
           COUNT(DISTINCT t.merchant)                                  AS merchant_cnt,
           COUNT(DISTINCT t.mcc)                                       AS mcc_cnt
    FROM card_txns t
    WHERE t.txn_date >= (TRUNC(SYSDATE) - 90)
    GROUP BY t.card_id
)
SELECT c.card_type,
       COUNT(*)                                     AS card_cnt,
       ROUND(SUM(cs.total_amt), 2)                  AS total_spend,
       ROUND(AVG(cs.total_amt), 2)                  AS avg_spend_per_card,
       ROUND(AVG(cs.txn_cnt), 1)                    AS avg_txn_cnt,
       ROUND(AVG(cs.total_amt) / NULLIF(AVG(c.credit_limit), 0) * 100, 2) AS utilization_pct,
       SUM(cs.overseas_cnt)                         AS overseas_txn_cnt,
       ROUND(SUM(cs.overseas_amt), 2)               AS overseas_amt
FROM card_stat cs
JOIN cards c ON cs.card_id = c.card_id
GROUP BY c.card_type
ORDER BY total_spend DESC;

-- ------------------------------------------------------------------------------
-- [152] 监控·境外交易 | 金融 | 境外交易监控与异常识别
-- ------------------------------------------------------------------------------
WITH ov AS (
    SELECT c.cust_id, t.card_id, t.txn_id, t.amount, t.txn_date, t.city, t.merchant, t.mcc,
           COUNT(*) OVER (PARTITION BY t.card_id ORDER BY t.txn_date
                          ROWS BETWEEN 10 PRECEDING AND CURRENT ROW) AS recent_txn_cnt
    FROM card_txns t
    JOIN cards c ON t.card_id = c.card_id
    WHERE t.is_overseas = 1
      AND t.txn_date >= (TRUNC(SYSDATE) - 180)
)
SELECT cust_id, card_id,
       COUNT(*)                          AS overseas_txn_cnt,
       ROUND(SUM(amount), 2)             AS overseas_amt,
       ROUND(AVG(amount), 2)             AS avg_amt,
       COUNT(DISTINCT city)              AS city_cnt,
       COUNT(DISTINCT merchant)          AS merchant_cnt,
       MAX(recent_txn_cnt)               AS peak_density,
       MIN(txn_date)                     AS first_overseas,
       MAX(txn_date)                     AS last_overseas,
       CASE WHEN COUNT(DISTINCT city) >= 5 AND SUM(amount) >= 200000 THEN 'high_risk'
            WHEN MAX(recent_txn_cnt) >= 8 THEN 'burst_pattern'
            ELSE 'normal' END AS overseas_risk_flag
FROM ov
GROUP BY cust_id, card_id
HAVING COUNT(*) >= 3
ORDER BY overseas_amt DESC
FETCH FIRST 300 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [153] MCC·商户类别 | 金融 | 商户类别（MCC）消费结构与风险分布
-- ------------------------------------------------------------------------------
SELECT t.mcc,
       COUNT(*)                    AS txn_cnt,
       COUNT(DISTINCT t.card_id)   AS card_cnt,
       ROUND(SUM(t.amount), 2)     AS total_amt,
       ROUND(AVG(t.amount), 2)     AS avg_amt,
       MAX(t.amount)               AS max_amt,
       SUM(CASE WHEN t.is_overseas = 1 THEN 1 ELSE 0 END) AS overseas_cnt,
       ROUND(SUM(t.amount) / NULLIF(SUM(SUM(t.amount)) OVER (), 0) * 100, 2) AS amt_share_pct,
       ROUND(SUM(CASE WHEN t.amount >= 50000 THEN 1 ELSE 0 END)
             / NULLIF(COUNT(*), 0) * 100, 2) AS large_txn_pct,
       CASE WHEN SUM(CASE WHEN t.amount >= 50000 THEN 1 ELSE 0 END)
                 / NULLIF(COUNT(*), 0) > 0.1 THEN 'high_value_mcc'
            ELSE 'normal' END AS mcc_risk_flag
FROM card_txns t
WHERE t.txn_date >= (TRUNC(SYSDATE) - 180)
GROUP BY t.mcc
ORDER BY total_amt DESC
FETCH FIRST 100 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [154] 额度·用信分析 | 金融 | 信用卡额度使用率与提额建议
-- ------------------------------------------------------------------------------
WITH card_usage AS (
    SELECT c.card_id, c.cust_id, c.credit_limit,
           COALESCE(SUM(t.amount), 0)                                   AS spend_90d,
           COUNT(t.txn_id)                                              AS txn_cnt
    FROM cards c
    LEFT JOIN card_txns t ON c.card_id = t.card_id
         AND t.txn_date >= (TRUNC(SYSDATE) - 90)
    WHERE c.status = 'active'
    GROUP BY c.card_id, c.cust_id, c.credit_limit
)
SELECT CASE WHEN spend_90d / NULLIF(credit_limit, 0) >= 0.9 THEN 'near_limit'
            WHEN spend_90d / NULLIF(credit_limit, 0) >= 0.5 THEN 'high_usage'
            WHEN spend_90d / NULLIF(credit_limit, 0) >= 0.2 THEN 'medium_usage'
            WHEN spend_90d > 0                              THEN 'low_usage'
            ELSE 'inactive' END AS usage_level,
       COUNT(*)                                     AS card_cnt,
       ROUND(AVG(credit_limit), 2)                  AS avg_limit,
       ROUND(AVG(spend_90d), 2)                     AS avg_spend,
       ROUND(AVG(spend_90d) / NULLIF(AVG(credit_limit), 0) * 100, 2) AS avg_utilization_pct,
       ROUND(AVG(txn_cnt), 1)                       AS avg_txn_cnt,
       CASE WHEN AVG(spend_90d) / NULLIF(AVG(credit_limit), 0) >= 0.7 THEN 'suggest_limit_increase'
            WHEN AVG(spend_90d) / NULLIF(AVG(credit_limit), 0) <= 0.1 THEN 'suggest_limit_decrease'
            ELSE 'maintain' END AS limit_action
FROM card_usage
GROUP BY CASE WHEN spend_90d / NULLIF(credit_limit, 0) >= 0.9 THEN 'near_limit'
              WHEN spend_90d / NULLIF(credit_limit, 0) >= 0.5 THEN 'high_usage'
              WHEN spend_90d / NULLIF(credit_limit, 0) >= 0.2 THEN 'medium_usage'
              WHEN spend_90d > 0                              THEN 'low_usage'
              ELSE 'inactive' END
ORDER BY avg_utilization_pct DESC;

-- ------------------------------------------------------------------------------
-- [155] 逾期·信用卡 | 金融 | 信用卡逾期分析与催收优先级
-- ------------------------------------------------------------------------------
WITH od AS (
    SELECT l.cust_id, l.loan_id,
           COUNT(r.repay_id)                                        AS total_due_cnt,
           SUM(CASE WHEN r.overdue_days > 0 THEN 1 ELSE 0 END)      AS overdue_cnt,
           MAX(r.overdue_days)                                      AS max_overdue_days,
           SUM(CASE WHEN r.status <> 'paid' THEN r.due_amount ELSE 0 END) AS outstanding_amt,
           SUM(CASE WHEN r.overdue_days > 0 THEN r.due_amount ELSE 0 END) AS overdue_amt
    FROM loans l
    JOIN repayments r ON l.loan_id = r.loan_id
    WHERE l.product_type = 'credit_card'
    GROUP BY l.cust_id, l.loan_id
)
SELECT c.cust_id, c.cust_name, c.risk_level,
       od.overdue_cnt, od.max_overdue_days,
       ROUND(od.overdue_amt, 2)      AS overdue_amt,
       ROUND(od.outstanding_amt, 2)  AS outstanding_amt,
       ROUND(od.overdue_cnt / NULLIF(od.total_due_cnt, 0) * 100, 2) AS overdue_rate_pct,
       CASE WHEN od.max_overdue_days > 90 THEN 'legal_action'
            WHEN od.max_overdue_days > 60 THEN 'outsourced_collection'
            WHEN od.max_overdue_days > 30 THEN 'phone_collection'
            WHEN od.max_overdue_days > 0  THEN 'sms_reminder'
            ELSE 'none' END AS collection_action
FROM od
JOIN fin_customers c ON od.cust_id = c.cust_id
WHERE od.overdue_cnt > 0
ORDER BY od.max_overdue_days DESC, overdue_amt DESC
FETCH FIRST 300 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [156] 套现·嫌疑识别 | 金融 | 信用卡套现嫌疑识别（大额整数 + 低频商户）
-- ------------------------------------------------------------------------------
WITH t AS (
    SELECT ct.card_id, ct.txn_id, ct.amount, ct.txn_date, ct.merchant, ct.mcc,
           MOD(FLOOR(ct.amount), 1000) AS mod_1000,
           COUNT(*) OVER (PARTITION BY ct.merchant) AS merchant_txn_all
    FROM card_txns ct
    WHERE ct.txn_date >= (TRUNC(SYSDATE) - 90)
)
SELECT card_id,
       COUNT(*)                                                    AS txn_cnt,
       SUM(CASE WHEN mod_1000 = 0 AND amount >= 5000 THEN 1 ELSE 0 END) AS round_big_cnt,
       ROUND(SUM(CASE WHEN mod_1000 = 0 AND amount >= 5000 THEN amount ELSE 0 END), 2) AS round_big_amt,
       COUNT(DISTINCT merchant)                                    AS merchant_cnt,
       MIN(merchant_txn_all)                                       AS min_merchant_popularity,
       ROUND(SUM(CASE WHEN mod_1000 = 0 AND amount >= 5000 THEN 1 ELSE 0 END)
             / NULLIF(COUNT(*), 0) * 100, 2)                       AS round_big_pct,
       CASE WHEN SUM(CASE WHEN mod_1000 = 0 AND amount >= 5000 THEN 1 ELSE 0 END) >= 5
                 AND COUNT(DISTINCT merchant) <= 2
                 AND MIN(merchant_txn_all) <= 10 THEN 'cash_out_suspect'
            WHEN SUM(CASE WHEN mod_1000 = 0 AND amount >= 5000 THEN 1 ELSE 0 END) >= 3 THEN 'watch'
            ELSE 'normal' END AS cash_out_flag
FROM t
GROUP BY card_id
HAVING SUM(CASE WHEN mod_1000 = 0 AND amount >= 5000 THEN 1 ELSE 0 END) >= 3
ORDER BY round_big_amt DESC
FETCH FIRST 200 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [157] 画像·交易行为 | 金融 | 客户交易行为画像（金额/频次/时段/渠道）
-- ------------------------------------------------------------------------------
WITH t AS (
    SELECT a.cust_id, t.amount, t.txn_date, t.channel, t.txn_type,
           EXTRACT(HOUR FROM CAST(t.txn_date AS TIMESTAMP)) AS hr
    FROM transactions t
    JOIN accounts a ON t.account_id = a.account_id
    WHERE t.txn_date >= (TRUNC(SYSDATE) - 180)
)
SELECT cust_id,
       COUNT(*)                                                          AS txn_cnt,
       ROUND(SUM(amount), 2)                                             AS total_amt,
       ROUND(AVG(amount), 2)                                             AS avg_amt,
       COUNT(DISTINCT channel)                                           AS channel_cnt,
       SUM(CASE WHEN hr >= 9 AND hr < 17 THEN 1 ELSE 0 END)              AS business_hour_cnt,
       SUM(CASE WHEN amount >= 50000 THEN 1 ELSE 0 END)                  AS large_cnt,
       ROUND(SUM(CASE WHEN hr >= 9 AND hr < 17 THEN 1 ELSE 0 END)
             / NULLIF(COUNT(*), 0) * 100, 2)                             AS business_hour_pct,
       CASE WHEN AVG(amount) >= 100000 THEN 'whale'
            WHEN COUNT(*) >= 200 THEN 'frequent_trader'
            WHEN SUM(CASE WHEN hr >= 9 AND hr < 17 THEN 1 ELSE 0 END)
                 / NULLIF(COUNT(*), 0) < 0.3 THEN 'night_owl'
            ELSE 'regular' END AS behavior_type
FROM t
GROUP BY cust_id
ORDER BY total_amt DESC
FETCH FIRST 500 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [158] 链路·资金追踪 | 金融 | 资金链路追踪（多层转账路径展开）
-- ------------------------------------------------------------------------------
WITH e1 AS (
    SELECT t.account_id AS src, a2.account_id AS lvl1,
           SUM(t.amount) AS amt1
    FROM transactions t
    JOIN accounts a2 ON t.counterparty = CAST(a2.account_id AS VARCHAR(20))
    WHERE t.txn_type = 'transfer_out'
      AND t.txn_date >= (TRUNC(SYSDATE) - 60)
    GROUP BY t.account_id, a2.account_id
),
e2 AS (
    SELECT e1.src AS src, e1.lvl1, t2.account_id AS lvl2,
           e1.amt1, t2.amount AS amt2
    FROM e1
    JOIN transactions t2 ON t2.account_id = e1.lvl1
    WHERE t2.txn_type = 'transfer_out'
      AND t2.txn_date >= (TRUNC(SYSDATE) - 60)
)
SELECT src AS origin_account,
       COUNT(DISTINCT lvl1)                   AS first_hop_accounts,
       COUNT(DISTINCT lvl2)                   AS second_hop_accounts,
       ROUND(SUM(amt1), 2)                    AS first_hop_amt,
       ROUND(SUM(amt2), 2)                    AS second_hop_amt,
       ROUND(SUM(amt2) / NULLIF(SUM(amt1), 0) * 100, 2) AS pass_through_pct,
       CASE WHEN COUNT(DISTINCT lvl2) >= 10 AND SUM(amt2) / NULLIF(SUM(amt1), 0) > 0.8
            THEN 'conduit_account' ELSE 'normal' END AS conduit_flag
FROM e2
GROUP BY src
HAVING COUNT(DISTINCT lvl1) >= 3
ORDER BY second_hop_accounts DESC
FETCH FIRST 200 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [159] 敞口·风险汇总 | 金融 | 客户风险敞口汇总（贷款 + 信用卡 + 担保）
-- ------------------------------------------------------------------------------
WITH loan_exp AS (
    SELECT cust_id, SUM(loan_amount) AS loan_exposure
    FROM loans WHERE status IN ('active', 'overdue') GROUP BY cust_id
),
card_exp AS (
    SELECT cust_id, SUM(credit_limit) AS card_exposure
    FROM cards WHERE status = 'active' GROUP BY cust_id
),
used AS (
    SELECT c.cust_id, SUM(t.amount) AS card_used
    FROM card_txns t
    JOIN cards c ON t.card_id = c.card_id
    WHERE t.txn_date >= (TRUNC(SYSDATE) - 30)
    GROUP BY c.cust_id
),
coll AS (
    SELECT cust_id, SUM(balance) AS collateral
    FROM accounts WHERE status = 'active' GROUP BY cust_id
)
SELECT c.cust_id, c.cust_name, c.cust_type, c.risk_level,
       ROUND(COALESCE(le.loan_exposure, 0), 2)  AS loan_exposure,
       ROUND(COALESCE(ce.card_exposure, 0), 2)  AS card_exposure,
       ROUND(COALESCE(u.card_used, 0), 2)       AS card_used_30d,
       ROUND(COALESCE(le.loan_exposure, 0) + COALESCE(ce.card_exposure, 0), 2) AS total_exposure,
       ROUND(COALESCE(co.collateral, 0), 2)     AS available_collateral,
       ROUND((COALESCE(le.loan_exposure, 0) + COALESCE(ce.card_exposure, 0))
             / NULLIF(COALESCE(co.collateral, 0), 0), 2) AS exposure_collateral_ratio,
       CASE WHEN (COALESCE(le.loan_exposure, 0) + COALESCE(ce.card_exposure, 0))
                 > COALESCE(co.collateral, 0) * 5 THEN 'over_exposed'
            WHEN (COALESCE(le.loan_exposure, 0) + COALESCE(ce.card_exposure, 0))
                 > COALESCE(co.collateral, 0) * 2 THEN 'elevated'
            ELSE 'adequate' END AS exposure_flag
FROM fin_customers c
LEFT JOIN loan_exp le ON c.cust_id = le.cust_id
LEFT JOIN card_exp ce ON c.cust_id = ce.cust_id
LEFT JOIN used      u  ON c.cust_id = u.cust_id
LEFT JOIN coll      co ON c.cust_id = co.cust_id
ORDER BY total_exposure DESC
FETCH FIRST 500 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [160] 看板·合规指标 | 金融 | 合规与风险监控日报（多指标汇总）
-- ------------------------------------------------------------------------------
SELECT CAST(t.txn_date AS DATE) AS dt,
       COUNT(*)                                                        AS total_txn,
       ROUND(SUM(t.amount), 2)                                         AS total_amt,
       SUM(CASE WHEN t.amount >= 200000 THEN 1 ELSE 0 END)             AS large_txn_cnt,
       SUM(CASE WHEN EXTRACT(HOUR FROM CAST(t.txn_date AS TIMESTAMP)) >= 20
                  OR EXTRACT(HOUR FROM CAST(t.txn_date AS TIMESTAMP)) < 8 THEN 1 ELSE 0 END)    AS offhour_cnt,
       SUM(CASE WHEN t.txn_type = 'transfer_out' THEN 1 ELSE 0 END)    AS out_cnt,
       SUM(CASE WHEN t.txn_type = 'transfer_in'  THEN 1 ELSE 0 END)    AS in_cnt,
       ROUND(SUM(CASE WHEN t.txn_type = 'transfer_out' THEN t.amount ELSE 0 END), 2) AS out_amt,
       ROUND(SUM(CASE WHEN t.txn_type = 'transfer_in'  THEN t.amount ELSE 0 END), 2) AS in_amt,
       ROUND(SUM(CASE WHEN t.txn_type = 'transfer_in' THEN t.amount ELSE 0 END)
             - SUM(CASE WHEN t.txn_type = 'transfer_out' THEN t.amount ELSE 0 END), 2) AS net_flow,
       COUNT(DISTINCT a.cust_id)                                       AS active_cust
FROM transactions t
JOIN accounts a ON t.account_id = a.account_id
WHERE t.txn_date >= (TRUNC(SYSDATE) - 30)
GROUP BY CAST(t.txn_date AS DATE)
ORDER BY dt DESC;

-- ------------------------------------------------------------------------------
-- [161] 账龄·贷款分析 | 金融 | 贷款账龄分析与逾期阶段分布
-- ------------------------------------------------------------------------------
WITH ln AS (
    SELECT l.loan_id, l.cust_id, l.product_type, l.branch_id, l.loan_amount,
           l.status,
           COALESCE(SUM(r.paid_amount), 0) AS paid_amt,
           COALESCE(MAX(r.overdue_days), 0) AS max_overdue,
           SUM(CASE WHEN r.status <> 'paid' THEN r.due_amount ELSE 0 END) AS outstanding
    FROM loans l
    LEFT JOIN repayments r ON l.loan_id = r.loan_id
    GROUP BY l.loan_id, l.cust_id, l.product_type, l.branch_id, l.loan_amount, l.status
)
SELECT CASE WHEN max_overdue = 0                THEN 'normal'
            WHEN max_overdue <= 30              THEN 'overdue_1_30'
            WHEN max_overdue <= 60              THEN 'overdue_31_60'
            WHEN max_overdue <= 90              THEN 'overdue_61_90'
            WHEN max_overdue <= 180             THEN 'overdue_91_180'
            ELSE 'overdue_180plus' END AS aging_bucket,
       COUNT(*)                          AS loan_cnt,
       ROUND(SUM(loan_amount), 2)        AS total_loan_amt,
       ROUND(SUM(outstanding), 2)        AS outstanding_amt,
       ROUND(SUM(outstanding) / NULLIF(SUM(loan_amount), 0) * 100, 2) AS outstanding_pct,
       ROUND(SUM(loan_amount) / NULLIF(SUM(SUM(loan_amount)) OVER (), 0) * 100, 2) AS amt_share_pct,
       ROUND(AVG(paid_amt / NULLIF(loan_amount, 0)) * 100, 2) AS avg_repayment_pct
FROM ln
GROUP BY CASE WHEN max_overdue = 0                THEN 'normal'
              WHEN max_overdue <= 30              THEN 'overdue_1_30'
              WHEN max_overdue <= 60              THEN 'overdue_31_60'
              WHEN max_overdue <= 90              THEN 'overdue_61_90'
              WHEN max_overdue <= 180             THEN 'overdue_91_180'
              ELSE 'overdue_180plus' END
ORDER BY total_loan_amt DESC;

-- ------------------------------------------------------------------------------
-- [162] 迁徙·逾期迁移 | 金融 | 逾期阶段迁徙矩阵（滚动率分析）
-- ------------------------------------------------------------------------------
WITH stage AS (
    SELECT r.loan_id, TO_CHAR(r.due_date, 'YYYY-MM') AS ym,
           MAX(r.overdue_days) AS max_od
    FROM repayments r
    GROUP BY r.loan_id, TO_CHAR(r.due_date, 'YYYY-MM')
),
bucketed AS (
    SELECT loan_id, ym,
           CASE WHEN max_od = 0   THEN 'normal'
                WHEN max_od <= 30 THEN 'm1'
                WHEN max_od <= 60 THEN 'm2'
                WHEN max_od <= 90 THEN 'm3'
                ELSE 'm4plus' END AS stage
    FROM stage
),
trans AS (
    SELECT loan_id, ym, stage,
           LAG(stage) OVER (PARTITION BY loan_id ORDER BY ym) AS prev_stage
    FROM bucketed
)
SELECT prev_stage AS from_stage, stage AS to_stage,
       COUNT(*)                                                     AS loan_cnt,
       ROUND(COUNT(*) / NULLIF(SUM(COUNT(*)) OVER (PARTITION BY prev_stage), 0) * 100, 2) AS roll_rate_pct,
       CASE WHEN prev_stage = 'normal' AND stage <> 'normal' THEN 'new_delinquent'
            WHEN prev_stage = 'm1' AND stage IN ('m2', 'm3', 'm4plus') THEN 'deteriorating'
            WHEN prev_stage <> 'normal' AND stage = 'normal' THEN 'cured'
            WHEN prev_stage = stage THEN 'stable'
            ELSE 'other' END AS migration_type
FROM trans
WHERE prev_stage IS NOT NULL
GROUP BY prev_stage, stage,
         CASE WHEN prev_stage = 'normal' AND stage <> 'normal' THEN 'new_delinquent'
              WHEN prev_stage = 'm1' AND stage IN ('m2', 'm3', 'm4plus') THEN 'deteriorating'
              WHEN prev_stage <> 'normal' AND stage = 'normal' THEN 'cured'
              WHEN prev_stage = stage THEN 'stable'
              ELSE 'other' END
ORDER BY from_stage, to_stage;

-- ------------------------------------------------------------------------------
-- [163] 回收·不良处置 | 金融 | 不良贷款回收率与核销分析
-- ------------------------------------------------------------------------------
WITH bad AS (
    SELECT l.loan_id, l.cust_id, l.product_type, l.loan_amount, l.branch_id,
           COALESCE(SUM(r.paid_amount), 0)  AS recovered,
           SUM(CASE WHEN r.status = 'written_off' THEN r.due_amount ELSE 0 END) AS written_off,
           MAX(r.overdue_days)              AS max_od
    FROM loans l
    JOIN repayments r ON l.loan_id = r.loan_id
    WHERE l.status IN ('overdue', 'default')
    GROUP BY l.loan_id, l.cust_id, l.product_type, l.loan_amount, l.branch_id
)
SELECT product_type,
       COUNT(*)                                                AS bad_loan_cnt,
       ROUND(SUM(loan_amount), 2)                              AS bad_loan_amt,
       ROUND(SUM(recovered), 2)                                AS recovered_amt,
       ROUND(SUM(written_off), 2)                              AS written_off_amt,
       ROUND(SUM(recovered) / NULLIF(SUM(loan_amount), 0) * 100, 2)  AS recovery_rate_pct,
       ROUND(SUM(written_off) / NULLIF(SUM(loan_amount), 0) * 100, 2) AS write_off_rate_pct,
       ROUND(AVG(max_od), 1)                                   AS avg_overdue_days,
       CASE WHEN SUM(recovered) / NULLIF(SUM(loan_amount), 0) >= 0.5 THEN 'good_recovery'
            WHEN SUM(recovered) / NULLIF(SUM(loan_amount), 0) >= 0.2 THEN 'moderate'
            ELSE 'poor_recovery' END AS recovery_grade
FROM bad
GROUP BY product_type
ORDER BY bad_loan_amt DESC;

-- ------------------------------------------------------------------------------
-- [164] 拨备·充足率 | 金融 | 贷款拨备计提与拨备充足率
-- ------------------------------------------------------------------------------
WITH prov AS (
    SELECT l.loan_id, l.product_type, l.branch_id, l.loan_amount, l.status,
           COALESCE(MAX(r.overdue_days), 0) AS max_od
    FROM loans l
    LEFT JOIN repayments r ON l.loan_id = r.loan_id
    GROUP BY l.loan_id, l.product_type, l.branch_id, l.loan_amount, l.status
),
calc AS (
    SELECT loan_id, product_type, branch_id, loan_amount,
           CASE WHEN max_od = 0 AND status = 'active' THEN loan_amount * 0.01
                WHEN max_od <= 30                     THEN loan_amount * 0.05
                WHEN max_od <= 90                     THEN loan_amount * 0.25
                WHEN max_od <= 180                    THEN loan_amount * 0.50
                ELSE loan_amount * 1.00 END AS required_provision,
           CASE WHEN max_od = 0 AND status = 'active' THEN 'normal'
                WHEN max_od <= 30                     THEN 'attention'
                WHEN max_od <= 90                     THEN 'substandard'
                WHEN max_od <= 180                    THEN 'doubtful'
                ELSE 'loss' END AS five_class
    FROM prov
)
SELECT branch_id, five_class,
       COUNT(*)                        AS loan_cnt,
       ROUND(SUM(loan_amount), 2)      AS loan_amt,
       ROUND(SUM(required_provision), 2) AS required_provision,
       ROUND(SUM(required_provision) / NULLIF(SUM(loan_amount), 0) * 100, 2) AS provision_rate_pct,
       ROUND(SUM(required_provision) / NULLIF(SUM(SUM(required_provision)) OVER (PARTITION BY branch_id), 0) * 100, 2) AS branch_provision_share_pct
FROM calc
GROUP BY branch_id, five_class
ORDER BY branch_id, five_class;

-- ------------------------------------------------------------------------------
-- [165] 收益·贷款定价 | 金融 | 贷款组合收益率与定价分析
-- ------------------------------------------------------------------------------
WITH ln AS (
    SELECT l.loan_id, l.product_type, l.branch_id, l.loan_amount, l.interest_rate,
           l.term_months, l.status,
           COALESCE(MAX(r.overdue_days), 0) AS max_od
    FROM loans l
    LEFT JOIN repayments r ON l.loan_id = r.loan_id
    GROUP BY l.loan_id, l.product_type, l.branch_id, l.loan_amount, l.interest_rate,
             l.term_months, l.status
)
SELECT product_type,
       COUNT(*)                                                  AS loan_cnt,
       ROUND(SUM(loan_amount), 2)                                AS total_amt,
       ROUND(AVG(interest_rate) * 100, 3)                        AS avg_rate_pct,
       ROUND(SUM(loan_amount * interest_rate)
             / NULLIF(SUM(loan_amount), 0) * 100, 3)             AS weighted_rate_pct,
       ROUND(AVG(term_months), 1)                                AS avg_term_months,
       SUM(CASE WHEN max_od > 90 THEN 1 ELSE 0 END)              AS bad_cnt,
       ROUND(SUM(CASE WHEN max_od > 90 THEN loan_amount ELSE 0 END)
             / NULLIF(SUM(loan_amount), 0) * 100, 2)             AS bad_amt_pct,
       ROUND(SUM(loan_amount * interest_rate) / NULLIF(SUM(loan_amount), 0) * 100
             - SUM(CASE WHEN max_od > 90 THEN loan_amount ELSE 0 END)
               / NULLIF(SUM(loan_amount), 0) * 100, 3)           AS risk_adj_yield_pct
FROM ln
GROUP BY product_type
ORDER BY weighted_rate_pct DESC;

-- ------------------------------------------------------------------------------
-- [166] 结构·期限分析 | 金融 | 贷款期限结构与重定价缺口
-- ------------------------------------------------------------------------------
SELECT CASE WHEN term_months <= 6   THEN 'short_0_6m'
            WHEN term_months <= 12  THEN 'medium_6_12m'
            WHEN term_months <= 36  THEN 'medium_1_3y'
            WHEN term_months <= 60  THEN 'long_3_5y'
            ELSE 'long_5y_plus' END AS term_band,
       COUNT(*)                                                AS loan_cnt,
       ROUND(SUM(loan_amount), 2)                              AS total_amt,
       ROUND(AVG(interest_rate) * 100, 3)                      AS avg_rate_pct,
       ROUND(AVG(term_months), 1)                              AS avg_term,
       SUM(CASE WHEN MONTHS_BETWEEN(end_date, TRUNC(SYSDATE)) <= 12 THEN 1 ELSE 0 END) AS maturing_12m,
       ROUND(SUM(CASE WHEN MONTHS_BETWEEN(end_date, TRUNC(SYSDATE)) <= 12 THEN loan_amount ELSE 0 END), 2) AS repricing_amt,
       ROUND(SUM(CASE WHEN MONTHS_BETWEEN(end_date, TRUNC(SYSDATE)) <= 12 THEN loan_amount ELSE 0 END)
             / NULLIF(SUM(loan_amount), 0) * 100, 2)           AS repricing_gap_pct
FROM loans
WHERE status IN ('active', 'overdue')
GROUP BY CASE WHEN term_months <= 6   THEN 'short_0_6m'
              WHEN term_months <= 12  THEN 'medium_6_12m'
              WHEN term_months <= 36  THEN 'medium_1_3y'
              WHEN term_months <= 60  THEN 'long_3_5y'
              ELSE 'long_5y_plus' END
ORDER BY total_amt DESC;

-- ------------------------------------------------------------------------------
-- [167] 多头·借贷识别 | 金融 | 多头借贷客户识别与共债风险
-- ------------------------------------------------------------------------------
WITH cust_loan AS (
    SELECT cust_id,
           COUNT(*)                                       AS loan_cnt,
           COUNT(DISTINCT product_type)                   AS product_cnt,
           COUNT(DISTINCT branch_id)                      AS branch_cnt,
           SUM(loan_amount)                               AS total_loan,
           SUM(CASE WHEN status IN ('overdue', 'default') THEN 1 ELSE 0 END) AS bad_cnt
    FROM loans
    GROUP BY cust_id
),
recent AS (
    SELECT cust_id, COUNT(*) AS new_loan_90d
    FROM loans
    WHERE start_date >= (TRUNC(SYSDATE) - 90)
    GROUP BY cust_id
)
SELECT CASE WHEN cl.product_cnt >= 4 OR COALESCE(r.new_loan_90d, 0) >= 3 THEN 'high_multi'
            WHEN cl.product_cnt >= 2 OR COALESCE(r.new_loan_90d, 0) >= 2 THEN 'medium_multi'
            ELSE 'single_source' END AS multi_borrowing_level,
       COUNT(*)                                          AS cust_cnt,
       ROUND(AVG(cl.total_loan), 2)                      AS avg_total_loan,
       SUM(cl.bad_cnt)                                   AS bad_loan_cnt,
       ROUND(SUM(cl.bad_cnt) / NULLIF(SUM(cl.loan_cnt), 0) * 100, 2) AS bad_rate_pct,
       ROUND(AVG(cl.branch_cnt), 2)                      AS avg_branch_cnt,
       CASE WHEN SUM(cl.bad_cnt) / NULLIF(SUM(cl.loan_cnt), 0) > 0.1 THEN 'high_risk_segment'
            ELSE 'normal' END AS segment_risk
FROM cust_loan cl
LEFT JOIN recent r ON cl.cust_id = r.cust_id
GROUP BY CASE WHEN cl.product_cnt >= 4 OR COALESCE(r.new_loan_90d, 0) >= 3 THEN 'high_multi'
              WHEN cl.product_cnt >= 2 OR COALESCE(r.new_loan_90d, 0) >= 2 THEN 'medium_multi'
              ELSE 'single_source' END
ORDER BY bad_rate_pct DESC;

-- ------------------------------------------------------------------------------
-- [168] 审批·通过率 | 金融 | 贷款申请通过率与客户画像关联
-- ------------------------------------------------------------------------------
WITH ln AS (
    SELECT l.loan_id, l.cust_id, l.product_type, l.status, l.loan_amount,
           c.cust_type, c.risk_level,
           COALESCE(s.avg_score, 0) AS credit_score
    FROM loans l
    JOIN fin_customers c ON l.cust_id = c.cust_id
    LEFT JOIN (SELECT cust_id, AVG(score) AS avg_score FROM credit_scores GROUP BY cust_id) s
           ON l.cust_id = s.cust_id
)
SELECT CASE WHEN credit_score >= 800 THEN 'excellent_800+'
            WHEN credit_score >= 740 THEN 'very_good_740_800'
            WHEN credit_score >= 670 THEN 'good_670_740'
            WHEN credit_score >= 580 THEN 'fair_580_670'
            ELSE 'poor_below_580' END AS score_band,
       COUNT(*)                                                          AS application_cnt,
       SUM(CASE WHEN status IN ('active', 'overdue', 'paid_off') THEN 1 ELSE 0 END) AS approved_cnt,
       SUM(CASE WHEN status = 'rejected' THEN 1 ELSE 0 END)              AS rejected_cnt,
       ROUND(SUM(CASE WHEN status IN ('active', 'overdue', 'paid_off') THEN 1 ELSE 0 END)
             / NULLIF(COUNT(*), 0) * 100, 2)                             AS approval_rate_pct,
       ROUND(AVG(CASE WHEN status IN ('active', 'overdue', 'paid_off') THEN loan_amount END), 2) AS avg_approved_amt
FROM ln
GROUP BY CASE WHEN credit_score >= 800 THEN 'excellent_800+'
              WHEN credit_score >= 740 THEN 'very_good_740_800'
              WHEN credit_score >= 670 THEN 'good_670_740'
              WHEN credit_score >= 580 THEN 'fair_580_670'
              ELSE 'poor_below_580' END
ORDER BY approval_rate_pct DESC;

-- ------------------------------------------------------------------------------
-- [169] 行为·还款分析 | 金融 | 客户还款行为分析与违约先导指标
-- ------------------------------------------------------------------------------
WITH rb AS (
    SELECT l.loan_id, l.cust_id,
           COUNT(r.repay_id)                                                   AS due_cnt,
           SUM(CASE WHEN r.overdue_days > 0 THEN 1 ELSE 0 END)                 AS late_cnt,
           SUM(CASE WHEN r.overdue_days > 0 AND r.overdue_days <= 7 THEN 1 ELSE 0 END) AS slight_late_cnt,
           AVG((r.repay_date - r.due_date))                           AS avg_pay_gap,
           MAX(r.overdue_days)                                                 AS max_od,
           SUM(r.paid_amount) / NULLIF(SUM(r.due_amount), 0)                   AS pay_ratio
    FROM loans l
    JOIN repayments r ON l.loan_id = r.loan_id
    GROUP BY l.loan_id, l.cust_id
)
SELECT CASE WHEN late_cnt = 0 THEN 'always_on_time'
            WHEN late_cnt / NULLIF(due_cnt, 0) <= 0.2 THEN 'mostly_on_time'
            WHEN late_cnt / NULLIF(due_cnt, 0) <= 0.5 THEN 'occasionally_late'
            ELSE 'frequently_late' END AS behavior_type,
       COUNT(*)                                        AS loan_cnt,
       ROUND(AVG(avg_pay_gap), 2)                      AS avg_payment_gap_days,
       ROUND(AVG(pay_ratio) * 100, 2)                  AS avg_pay_ratio_pct,
       ROUND(AVG(max_od), 1)                           AS avg_max_overdue,
       SUM(CASE WHEN max_od > 90 THEN 1 ELSE 0 END)    AS severe_default_cnt,
       ROUND(SUM(CASE WHEN max_od > 90 THEN 1 ELSE 0 END)
             / NULLIF(COUNT(*), 0) * 100, 2)           AS default_rate_pct
FROM rb
GROUP BY CASE WHEN late_cnt = 0 THEN 'always_on_time'
              WHEN late_cnt / NULLIF(due_cnt, 0) <= 0.2 THEN 'mostly_on_time'
              WHEN late_cnt / NULLIF(due_cnt, 0) <= 0.5 THEN 'occasionally_late'
              ELSE 'frequently_late' END
ORDER BY default_rate_pct DESC;

-- ------------------------------------------------------------------------------
-- [170] 提前·还款分析 | 金融 | 提前还款识别与利息损失测算
-- ------------------------------------------------------------------------------
WITH rp AS (
    SELECT l.loan_id, l.cust_id, l.product_type, l.loan_amount, l.interest_rate,
           l.term_months, l.start_date, l.end_date,
           MIN(r.repay_date) AS first_pay,
           MAX(r.repay_date) AS last_pay,
           SUM(r.paid_amount) AS total_paid,
           COUNT(r.repay_id)  AS actual_periods
    FROM loans l
    JOIN repayments r ON l.loan_id = r.loan_id
    WHERE r.status = 'paid'
    GROUP BY l.loan_id, l.cust_id, l.product_type, l.loan_amount, l.interest_rate,
             l.term_months, l.start_date, l.end_date
)
SELECT product_type,
       COUNT(*)                                                        AS loan_cnt,
       SUM(CASE WHEN actual_periods < term_months THEN 1 ELSE 0 END)   AS prepaid_cnt,
       ROUND(SUM(CASE WHEN actual_periods < term_months THEN 1 ELSE 0 END)
             / NULLIF(COUNT(*), 0) * 100, 2)                           AS prepay_rate_pct,
       ROUND(AVG(term_months - actual_periods), 1)                     AS avg_months_saved,
       ROUND(SUM(CASE WHEN actual_periods < term_months
                      THEN (term_months - actual_periods) * loan_amount * interest_rate / 100 / 12
                      ELSE 0 END), 2)                                  AS interest_loss,
       ROUND(AVG(MONTHS_BETWEEN(last_pay, start_date)), 1)               AS avg_actual_months
FROM rp
GROUP BY product_type
ORDER BY prepay_rate_pct DESC;

-- ------------------------------------------------------------------------------
-- [171] 盈利·产品对比 | 金融 | 贷款产品盈利能力对比（收益-风险-成本）
-- ------------------------------------------------------------------------------
WITH ln AS (
    SELECT l.loan_id, l.product_type, l.loan_amount, l.interest_rate, l.term_months,
           COALESCE(MAX(r.overdue_days), 0) AS max_od,
           COALESCE(SUM(r.paid_amount), 0)  AS paid_amt
    FROM loans l
    LEFT JOIN repayments r ON l.loan_id = r.loan_id
    GROUP BY l.loan_id, l.product_type, l.loan_amount, l.interest_rate, l.term_months
)
SELECT product_type,
       COUNT(*)                                                              AS loan_cnt,
       ROUND(SUM(loan_amount), 2)                                            AS total_amt,
       ROUND(SUM(loan_amount * interest_rate / 100 * term_months / 12), 2)   AS expected_interest,
       ROUND(SUM(loan_amount * 0.02), 2)                                     AS operating_cost,
       ROUND(SUM(CASE WHEN max_od > 90 THEN loan_amount * 0.6
                      WHEN max_od > 30 THEN loan_amount * 0.2 ELSE 0 END), 2) AS expected_loss,
       ROUND(SUM(loan_amount * interest_rate / 100 * term_months / 12)
             - SUM(loan_amount * 0.02)
             - SUM(CASE WHEN max_od > 90 THEN loan_amount * 0.6
                        WHEN max_od > 30 THEN loan_amount * 0.2 ELSE 0 END), 2) AS net_profit,
       ROUND((SUM(loan_amount * interest_rate / 100 * term_months / 12)
              - SUM(loan_amount * 0.02)
              - SUM(CASE WHEN max_od > 90 THEN loan_amount * 0.6
                         WHEN max_od > 30 THEN loan_amount * 0.2 ELSE 0 END))
             / NULLIF(SUM(loan_amount), 0) * 100, 2)                         AS roa_pct
FROM ln
GROUP BY product_type
ORDER BY net_profit DESC;

-- ------------------------------------------------------------------------------
-- [172] 偿债·负债分析 | 金融 | 客户负债率与偿债能力评估
-- ------------------------------------------------------------------------------
WITH debt AS (
    SELECT cust_id,
           SUM(CASE WHEN status IN ('active', 'overdue') THEN loan_amount ELSE 0 END) AS total_debt,
           SUM(CASE WHEN status IN ('active', 'overdue')
                    THEN loan_amount * interest_rate / 100 / 12 ELSE 0 END) AS monthly_payment
    FROM loans GROUP BY cust_id
),
asset AS (
    SELECT cust_id, SUM(balance) AS total_asset
    FROM accounts WHERE status = 'active' GROUP BY cust_id
),
inc_proxy AS (
    SELECT cust_id, SUM(balance) * 0.003 AS est_monthly_income
    FROM accounts WHERE status = 'active' GROUP BY cust_id
)
SELECT CASE WHEN d.total_debt / NULLIF(a.total_asset, 0) >= 5      THEN 'over_leveraged'
            WHEN d.total_debt / NULLIF(a.total_asset, 0) >= 2      THEN 'high_leverage'
            WHEN d.total_debt / NULLIF(a.total_asset, 0) >= 0.5    THEN 'moderate'
            ELSE 'conservative' END AS leverage_level,
       COUNT(*)                                                    AS cust_cnt,
       ROUND(AVG(d.total_debt), 2)                                 AS avg_debt,
       ROUND(AVG(a.total_asset), 2)                                AS avg_asset,
       ROUND(AVG(d.total_debt / NULLIF(a.total_asset, 0)), 2)      AS avg_debt_ratio,
       ROUND(AVG(d.monthly_payment / NULLIF(i.est_monthly_income, 0)) * 100, 2) AS avg_dsr_pct,
       SUM(CASE WHEN d.monthly_payment / NULLIF(i.est_monthly_income, 0) > 0.5 THEN 1 ELSE 0 END) AS dsr_breach_cnt
FROM debt d
LEFT JOIN asset     a ON d.cust_id = a.cust_id
LEFT JOIN inc_proxy i ON d.cust_id = i.cust_id
GROUP BY CASE WHEN d.total_debt / NULLIF(a.total_asset, 0) >= 5      THEN 'over_leveraged'
              WHEN d.total_debt / NULLIF(a.total_asset, 0) >= 2      THEN 'high_leverage'
              WHEN d.total_debt / NULLIF(a.total_asset, 0) >= 0.5    THEN 'moderate'
              ELSE 'conservative' END
ORDER BY avg_debt_ratio DESC;

-- ------------------------------------------------------------------------------
-- [173] 集中·贷款分布 | 金融 | 贷款集中度分析（产品/分支/客户）
-- ------------------------------------------------------------------------------
WITH total AS (
    SELECT SUM(loan_amount) AS all_loan FROM loans WHERE status IN ('active', 'overdue')
),
by_dim AS (
    SELECT 'product' AS dim_type, product_type AS dim_value, SUM(loan_amount) AS amt
    FROM loans WHERE status IN ('active', 'overdue') GROUP BY product_type
    UNION ALL
    SELECT 'branch', CAST(branch_id AS VARCHAR(10)), SUM(loan_amount)
    FROM loans WHERE status IN ('active', 'overdue') GROUP BY branch_id
    UNION ALL
    SELECT 'cust', CAST(cust_id AS VARCHAR(20)), SUM(loan_amount)
    FROM loans WHERE status IN ('active', 'overdue') GROUP BY cust_id
)
SELECT dim_type, dim_value, ROUND(amt, 2) AS amt,
       ROUND(amt / NULLIF(t.all_loan, 0) * 100, 2) AS concentration_pct,
       ROW_NUMBER() OVER (PARTITION BY dim_type ORDER BY amt DESC) AS rank_in_dim,
       CASE WHEN amt / NULLIF(t.all_loan, 0) > 0.10 THEN 'concentration_risk'
            WHEN amt / NULLIF(t.all_loan, 0) > 0.05 THEN 'monitor'
            ELSE 'normal' END AS risk_flag
FROM by_dim
CROSS JOIN total t
ORDER BY dim_type, amt DESC
FETCH FIRST 100 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [174] RWA·风险资产 | 金融 | 风险加权资产（RWA）测算与资本占用
-- ------------------------------------------------------------------------------
WITH rw AS (
    SELECT l.loan_id, l.product_type, l.branch_id, l.loan_amount, l.status,
           CASE l.product_type WHEN 'mortgage'      THEN 0.35
                               WHEN 'auto'          THEN 0.75
                               WHEN 'credit_card'   THEN 0.75
                               WHEN 'personal'      THEN 1.00
                               WHEN 'corporate'     THEN 1.00
                               ELSE 1.00 END AS risk_weight,
           COALESCE(MAX(r.overdue_days), 0) AS max_od
    FROM loans l
    LEFT JOIN repayments r ON l.loan_id = r.loan_id
    WHERE l.status IN ('active', 'overdue')
    GROUP BY l.loan_id, l.product_type, l.branch_id, l.loan_amount, l.status
)
SELECT branch_id,
       COUNT(*)                                                          AS loan_cnt,
       ROUND(SUM(loan_amount), 2)                                        AS exposure,
       ROUND(SUM(loan_amount * risk_weight), 2)                          AS rwa_basic,
       ROUND(SUM(CASE WHEN max_od > 90 THEN loan_amount * risk_weight * 1.5
                      ELSE loan_amount * risk_weight END), 2)            AS rwa_adjusted,
       ROUND(SUM(loan_amount * risk_weight) / NULLIF(SUM(loan_amount), 0), 3) AS avg_risk_weight,
       ROUND(SUM(loan_amount * risk_weight) * 0.08, 2)                   AS capital_requirement,
       ROUND(SUM(loan_amount * risk_weight) * 0.08
             / NULLIF(SUM(SUM(loan_amount * risk_weight)) OVER (), 0) * 100, 2) AS capital_share_pct
FROM rw
GROUP BY branch_id
ORDER BY rwa_adjusted DESC;

-- ------------------------------------------------------------------------------
-- [175] ECL·预期损失 | 金融 | 预期信用损失（ECL）三阶段测算
-- ------------------------------------------------------------------------------
WITH loan_stage AS (
    SELECT l.loan_id, l.product_type, l.loan_amount, l.term_months,
           COALESCE(MAX(r.overdue_days), 0) AS max_od,
           SUM(CASE WHEN r.status <> 'paid' THEN r.due_amount ELSE 0 END) AS ead
    FROM loans l
    LEFT JOIN repayments r ON l.loan_id = r.loan_id
    WHERE l.status IN ('active', 'overdue')
    GROUP BY l.loan_id, l.product_type, l.loan_amount, l.term_months
),
ecl AS (
    SELECT loan_id, product_type, loan_amount, term_months,
           COALESCE(ead, loan_amount) AS exposure,
           CASE WHEN max_od = 0   THEN 1
                WHEN max_od <= 90 THEN 2
                ELSE 3 END AS stage,
           CASE WHEN max_od = 0   THEN 0.01
                WHEN max_od <= 90 THEN 0.15
                ELSE 0.60 END AS pd,
           CASE WHEN max_od = 0   THEN 0.25
                WHEN max_od <= 90 THEN 0.40
                ELSE 0.70 END AS lgd
    FROM loan_stage
)
SELECT product_type, stage,
       COUNT(*)                                                AS loan_cnt,
       ROUND(SUM(exposure), 2)                                 AS ead_total,
       ROUND(SUM(exposure * pd * lgd), 2)                      AS ecl_12m,
       ROUND(SUM(CASE WHEN stage = 3 THEN exposure * pd * lgd
                      ELSE exposure * pd * lgd * term_months / 12 END), 2) AS ecl_lifetime,
       ROUND(SUM(exposure * pd * lgd) / NULLIF(SUM(exposure), 0) * 100, 3) AS ecl_ratio_pct,
       ROUND(SUM(exposure * pd * lgd)
             / NULLIF(SUM(SUM(exposure * pd * lgd)) OVER (PARTITION BY product_type), 0) * 100, 2) AS stage_ecl_share_pct
FROM ecl
GROUP BY product_type, stage
ORDER BY product_type, stage;

-- ------------------------------------------------------------------------------
-- [176] 趋势·贷款发放 | 金融 | 贷款发放趋势与季节性分析
-- ------------------------------------------------------------------------------
WITH m AS (
    SELECT TO_CHAR(start_date, 'YYYY-MM') AS ym, product_type,
           COUNT(*)          AS loan_cnt,
           SUM(loan_amount)  AS loan_amt
    FROM loans
    WHERE start_date >= (TRUNC(SYSDATE) - 730)
    GROUP BY TO_CHAR(start_date, 'YYYY-MM'), product_type
)
SELECT ym, product_type, loan_cnt, ROUND(loan_amt, 2) AS loan_amt,
       LAG(loan_amt) OVER (PARTITION BY product_type ORDER BY ym) AS prev_amt,
       ROUND((loan_amt - LAG(loan_amt) OVER (PARTITION BY product_type ORDER BY ym))
             / NULLIF(LAG(loan_amt) OVER (PARTITION BY product_type ORDER BY ym), 0) * 100, 2) AS mom_pct,
       ROUND(AVG(loan_amt) OVER (PARTITION BY product_type ORDER BY ym
             ROWS BETWEEN 2 PRECEDING AND CURRENT ROW), 2) AS ma3,
       ROUND(SUM(loan_amt) OVER (PARTITION BY product_type ORDER BY ym
             ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW), 2) AS cum_amt
FROM m
ORDER BY product_type, ym;

-- ------------------------------------------------------------------------------
-- [177] 业绩·信贷经理 | 金融 | 信贷经理业绩排名与资产质量
-- ------------------------------------------------------------------------------
WITH mgr AS (
    SELECT b.manager_id, l.loan_id, l.loan_amount, l.status, l.branch_id,
           COALESCE(MAX(r.overdue_days), 0) AS max_od
    FROM loans l
    JOIN branches b ON l.branch_id = b.branch_id
    LEFT JOIN repayments r ON l.loan_id = r.loan_id
    GROUP BY b.manager_id, l.loan_id, l.loan_amount, l.status, l.branch_id
)
SELECT manager_id,
       COUNT(*)                                                        AS loan_cnt,
       ROUND(SUM(loan_amount), 2)                                      AS total_amt,
       ROUND(AVG(loan_amount), 2)                                      AS avg_amt,
       SUM(CASE WHEN max_od > 90 THEN 1 ELSE 0 END)                    AS bad_cnt,
       ROUND(SUM(CASE WHEN max_od > 90 THEN loan_amount ELSE 0 END)
             / NULLIF(SUM(loan_amount), 0) * 100, 2)                   AS npl_pct,
       RANK() OVER (ORDER BY SUM(loan_amount) DESC)                    AS volume_rank,
       RANK() OVER (ORDER BY SUM(CASE WHEN max_od > 90 THEN loan_amount ELSE 0 END)
             / NULLIF(SUM(loan_amount), 0) ASC)                        AS quality_rank,
       ROUND(SUM(loan_amount) / NULLIF(SUM(SUM(loan_amount)) OVER (), 0) * 100, 2) AS volume_share_pct,
       CASE WHEN SUM(CASE WHEN max_od > 90 THEN loan_amount ELSE 0 END)
                 / NULLIF(SUM(loan_amount), 0) > 0.05 THEN 'quality_alert'
            ELSE 'ok' END AS quality_flag
FROM mgr
GROUP BY manager_id
ORDER BY total_amt DESC;

-- ------------------------------------------------------------------------------
-- [178] 催收·效果分析 | 金融 | 逾期催收效果与回收率分析
-- ------------------------------------------------------------------------------
WITH od AS (
    SELECT r.repay_id, l.loan_id, l.cust_id, l.product_type, r.due_amount, r.paid_amount,
           r.overdue_days, r.due_date, r.repay_date,
           CASE WHEN r.overdue_days <= 30 THEN 'stage_1_30'
                WHEN r.overdue_days <= 60 THEN 'stage_31_60'
                WHEN r.overdue_days <= 90 THEN 'stage_61_90'
                ELSE 'stage_90plus' END AS od_stage
    FROM repayments r
    JOIN loans l ON r.loan_id = l.loan_id
    WHERE r.overdue_days > 0
)
SELECT od_stage,
       COUNT(*)                                                   AS overdue_cnt,
       ROUND(SUM(due_amount), 2)                                  AS overdue_amt,
       ROUND(SUM(paid_amount), 2)                                 AS recovered_amt,
       ROUND(SUM(paid_amount) / NULLIF(SUM(due_amount), 0) * 100, 2) AS recovery_rate_pct,
       ROUND(AVG((repay_date - due_date)), 1)            AS avg_recovery_days,
       SUM(CASE WHEN paid_amount >= due_amount THEN 1 ELSE 0 END) AS fully_recovered_cnt,
       ROUND(SUM(CASE WHEN paid_amount >= due_amount THEN 1 ELSE 0 END)
             / NULLIF(COUNT(*), 0) * 100, 2)                      AS full_recovery_pct
FROM od
GROUP BY od_stage
ORDER BY recovery_rate_pct DESC;

-- ------------------------------------------------------------------------------
-- [179] 展期·贷款重组 | 金融 | 贷款展期与重组识别
-- ------------------------------------------------------------------------------
WITH orig AS (
    SELECT loan_id, cust_id, product_type, loan_amount, term_months,
           start_date, end_date,
           MONTHS_BETWEEN(end_date, start_date) AS actual_months
    FROM loans
)
SELECT product_type,
       COUNT(*)                                                            AS loan_cnt,
       SUM(CASE WHEN term_months <> actual_months THEN 1 ELSE 0 END)       AS term_mismatch_cnt,
       ROUND(AVG(term_months), 1)                                          AS avg_declared_term,
       ROUND(AVG(actual_months), 1)                                        AS avg_actual_term,
       ROUND(AVG(actual_months - term_months), 1)                          AS avg_extension_months,
       SUM(CASE WHEN actual_months > term_months THEN 1 ELSE 0 END)        AS extended_cnt,
       ROUND(SUM(CASE WHEN actual_months > term_months THEN 1 ELSE 0 END)
             / NULLIF(COUNT(*), 0) * 100, 2)                               AS extension_rate_pct,
       ROUND(SUM(CASE WHEN actual_months > term_months THEN loan_amount ELSE 0 END), 2) AS extended_amt
FROM orig
GROUP BY product_type
ORDER BY extension_rate_pct DESC;

-- ------------------------------------------------------------------------------
-- [180] 看板·信贷质量 | 金融 | 信贷组合质量综合看板
-- ------------------------------------------------------------------------------
SELECT TO_CHAR(l.start_date, 'YYYY-MM') AS vintage,
       COUNT(*)                                                        AS loan_cnt,
       ROUND(SUM(l.loan_amount), 2)                                    AS origination_amt,
       SUM(CASE WHEN l.status IN ('overdue', 'default') THEN 1 ELSE 0 END) AS bad_cnt,
       ROUND(SUM(CASE WHEN l.status IN ('overdue', 'default') THEN l.loan_amount ELSE 0 END), 2) AS bad_amt,
       ROUND(SUM(CASE WHEN l.status IN ('overdue', 'default') THEN l.loan_amount ELSE 0 END)
             / NULLIF(SUM(l.loan_amount), 0) * 100, 2)                 AS npl_by_vintage_pct,
       ROUND(AVG(l.interest_rate) * 100, 3)                            AS avg_rate_pct,
       ROUND(AVG(l.term_months), 1)                                    AS avg_term,
       ROUND(SUM(l.loan_amount) / NULLIF(SUM(SUM(l.loan_amount)) OVER (), 0) * 100, 2) AS vintage_share_pct
FROM loans l
WHERE l.start_date >= (TRUNC(SYSDATE) - 1095)
GROUP BY TO_CHAR(l.start_date, 'YYYY-MM')
ORDER BY vintage DESC;

-- ------------------------------------------------------------------------------
-- [181] 净值·基金收益 | 金融 | 基金净值增长率与累计收益分析
-- ------------------------------------------------------------------------------
WITH n AS (
    SELECT fund_id, nav_date, nav,
           LAG(nav) OVER (PARTITION BY fund_id ORDER BY nav_date) AS prev_nav,
           FIRST_VALUE(nav) OVER (PARTITION BY fund_id ORDER BY nav_date) AS base_nav,
           MAX(nav) OVER (PARTITION BY fund_id ORDER BY nav_date
                ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS peak_nav
    FROM fund_nav
    WHERE nav_date >= (TRUNC(SYSDATE) - 365)
)
SELECT f.fund_name, f.fund_type,
       COUNT(*)                                                       AS nav_days,
       MAX(n.nav_date)                                                AS latest_date,
       ROUND(MAX(CASE WHEN n.nav_date = (SELECT MAX(nav_date) FROM fund_nav n2 WHERE n2.fund_id = n.fund_id) THEN n.nav END), 4) AS latest_nav,
       ROUND(MAX(CASE WHEN n.nav_date = (SELECT MAX(nav_date) FROM fund_nav n3 WHERE n3.fund_id = n.fund_id) THEN n.nav END)
             / NULLIF(MAX(n.base_nav), 0) * 100 - 100, 2)             AS total_return_pct,
       ROUND(AVG((n.nav - n.prev_nav) / NULLIF(n.prev_nav, 0)) * 100, 4) AS avg_daily_return_pct,
       ROUND(MIN((n.nav - n.peak_nav) / NULLIF(n.peak_nav, 0)) * 100, 2) AS max_drawdown_pct
FROM n
JOIN fund_products f ON n.fund_id = f.fund_id
GROUP BY f.fund_name, f.fund_type, n.fund_id
ORDER BY total_return_pct DESC
FETCH FIRST 200 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [182] 回撤·风险控制 | 金融 | 基金最大回撤与恢复期分析
-- ------------------------------------------------------------------------------
WITH n AS (
    SELECT fund_id, nav_date, nav,
           MAX(nav) OVER (PARTITION BY fund_id ORDER BY nav_date
                ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS running_peak
    FROM fund_nav
    WHERE nav_date >= (TRUNC(SYSDATE) - 730)
),
dd AS (
    SELECT fund_id, nav_date, nav, running_peak,
           (nav - running_peak) / NULLIF(running_peak, 0) AS drawdown
    FROM n
)
SELECT f.fund_name, f.fund_type, f.risk_level,
       COUNT(*)                                        AS trading_days,
       ROUND(MIN(dd.drawdown) * 100, 2)                AS max_drawdown_pct,
       ROUND(AVG(dd.drawdown) * 100, 2)                AS avg_drawdown_pct,
       SUM(CASE WHEN dd.drawdown < -0.05 THEN 1 ELSE 0 END) AS deep_dd_days,
       ROUND(SUM(CASE WHEN dd.drawdown < -0.05 THEN 1 ELSE 0 END)
             / NULLIF(COUNT(*), 0) * 100, 2)           AS deep_dd_pct,
       SUM(CASE WHEN dd.drawdown = 0 THEN 1 ELSE 0 END) AS at_peak_days,
       CASE WHEN MIN(dd.drawdown) < -0.3 THEN 'high_volatility'
            WHEN MIN(dd.drawdown) < -0.15 THEN 'moderate_volatility'
            ELSE 'low_volatility' END AS volatility_grade
FROM dd
JOIN fund_products f ON dd.fund_id = f.fund_id
GROUP BY f.fund_name, f.fund_type, f.risk_level, dd.fund_id
ORDER BY max_drawdown_pct ASC
FETCH FIRST 200 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [183] 夏普·风险调整 | 金融 | 基金夏普比率与风险调整收益排名
-- ------------------------------------------------------------------------------
WITH r AS (
    SELECT fund_id, nav_date, nav,
           (nav - LAG(nav) OVER (PARTITION BY fund_id ORDER BY nav_date))
             / NULLIF(LAG(nav) OVER (PARTITION BY fund_id ORDER BY nav_date), 0) AS daily_ret
    FROM fund_nav
    WHERE nav_date >= (TRUNC(SYSDATE) - 365)
),
stat AS (
    SELECT fund_id,
           COUNT(daily_ret)                                    AS n_days,
           AVG(daily_ret)                                      AS mean_ret,
           SUM(daily_ret * daily_ret) / NULLIF(COUNT(daily_ret), 0)
             - AVG(daily_ret) * AVG(daily_ret)                 AS variance
    FROM r
    WHERE daily_ret IS NOT NULL
    GROUP BY fund_id
    HAVING COUNT(daily_ret) >= 30
)
SELECT f.fund_name, f.fund_type, f.risk_level,
       s.n_days,
       ROUND(s.mean_ret * 252 * 100, 2)                                       AS annualized_return_pct,
       ROUND(CASE WHEN s.variance > 0 THEN SQRT(s.variance * 252) * 100 ELSE 0 END, 2) AS annualized_vol_pct,
       ROUND(CASE WHEN s.variance > 0
                  THEN (s.mean_ret * 252 - 0.02) / NULLIF(SQRT(s.variance * 252), 0)
                  ELSE 0 END, 3)                                              AS sharpe_ratio,
       RANK() OVER (ORDER BY CASE WHEN s.variance > 0
                  THEN (s.mean_ret * 252 - 0.02) / NULLIF(SQRT(s.variance * 252), 0)
                  ELSE 0 END DESC)                                            AS sharpe_rank
FROM stat s
JOIN fund_products f ON s.fund_id = f.fund_id
ORDER BY sharpe_ratio DESC
FETCH FIRST 200 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [184] 排名·基金业绩 | 金融 | 基金业绩排名与同类分位数
-- ------------------------------------------------------------------------------
WITH perf AS (
    SELECT fund_id,
           MAX(nav) AS latest_nav,
           MIN(nav) AS min_nav,
           AVG(nav) AS avg_nav
    FROM fund_nav
    WHERE nav_date >= (TRUNC(SYSDATE) - 365)
    GROUP BY fund_id
),
chg AS (
    SELECT p.fund_id,
           (p.latest_nav - p.avg_nav) / NULLIF(p.avg_nav, 0) AS nav_change
    FROM perf p
)
SELECT f.fund_name, f.fund_type, f.risk_level,
       ROUND(c.nav_change * 100, 2) AS nav_change_pct,
       ROUND(CAST(PERCENT_RANK() OVER (PARTITION BY f.fund_type ORDER BY c.nav_change) * 100 AS NUMBER), 1) AS pct_in_type,
       RANK() OVER (PARTITION BY f.fund_type ORDER BY c.nav_change DESC) AS rank_in_type,
       RANK() OVER (ORDER BY c.nav_change DESC)                          AS rank_overall,
       COUNT(*) OVER (PARTITION BY f.fund_type)                          AS peers_cnt,
       CASE WHEN PERCENT_RANK() OVER (PARTITION BY f.fund_type ORDER BY c.nav_change) >= 0.8 THEN 'top20pct'
            WHEN PERCENT_RANK() OVER (PARTITION BY f.fund_type ORDER BY c.nav_change) <= 0.2 THEN 'bottom20pct'
            ELSE 'middle' END AS performance_tier
FROM chg c
JOIN fund_products f ON c.fund_id = f.fund_id
ORDER BY rank_in_type;

-- ------------------------------------------------------------------------------
-- [185] 波动·基金风险 | 金融 | 基金净值波动率与下行风险
-- ------------------------------------------------------------------------------
WITH r AS (
    SELECT fund_id, nav_date,
           (nav - LAG(nav) OVER (PARTITION BY fund_id ORDER BY nav_date))
             / NULLIF(LAG(nav) OVER (PARTITION BY fund_id ORDER BY nav_date), 0) AS ret
    FROM fund_nav
    WHERE nav_date >= (TRUNC(SYSDATE) - 365)
),
stat AS (
    SELECT fund_id,
           AVG(ret)                                                     AS mean_ret,
           COUNT(ret)                                                   AS n,
           SUM(CASE WHEN ret < 0 THEN ret * ret ELSE 0 END)             AS neg_sq_sum,
           SUM(CASE WHEN ret < 0 THEN 1 ELSE 0 END)                     AS neg_cnt,
           MIN(ret)                                                     AS worst_ret,
           MAX(ret)                                                     AS best_ret
    FROM r WHERE ret IS NOT NULL GROUP BY fund_id
)
SELECT f.fund_name, f.fund_type, f.risk_level,
       ROUND(AVG(s.mean_ret) * 252 * 100, 2)                                    AS annual_return_pct,
       ROUND(MIN(s.worst_ret) * 100, 2)                                         AS worst_day_pct,
       ROUND(MAX(s.best_ret) * 100, 2)                                          AS best_day_pct,
       ROUND(CASE WHEN s.neg_cnt > 0 THEN SQRT(s.neg_sq_sum / s.neg_cnt * 252) * 100 ELSE 0 END, 2) AS downside_vol_pct,
       ROUND(s.neg_cnt / NULLIF(s.n, 0) * 100, 2)                               AS negative_day_pct,
       ROUND(CASE WHEN SQRT(s.neg_sq_sum / NULLIF(s.neg_cnt, 0) * 252) > 0
                  THEN (s.mean_ret * 252) / NULLIF(SQRT(s.neg_sq_sum / s.neg_cnt * 252), 0)
                  ELSE 0 END, 3)                                                AS sortino_ratio
FROM stat s
JOIN fund_products f ON s.fund_id = f.fund_id
GROUP BY f.fund_name, f.fund_type, f.risk_level, s.mean_ret, s.neg_sq_sum, s.neg_cnt, s.n, s.worst_ret, s.best_ret
ORDER BY sortino_ratio DESC
FETCH FIRST 200 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [186] 持仓·客户收益 | 金融 | 客户基金持仓收益与浮动盈亏
-- ------------------------------------------------------------------------------
WITH latest AS (
    SELECT fund_id, nav_date, nav,
           ROW_NUMBER() OVER (PARTITION BY fund_id ORDER BY nav_date DESC) AS rn
    FROM fund_nav
)
SELECT c.cust_id, c.cust_name,
       COUNT(DISTINCT h.fund_id)                                          AS fund_cnt,
       ROUND(SUM(h.cost_amount), 2)                                       AS total_cost,
       ROUND(SUM(h.shares * l.nav), 2)                                    AS market_value,
       ROUND(SUM(h.shares * l.nav) - SUM(h.cost_amount), 2)               AS unrealized_pnl,
       ROUND((SUM(h.shares * l.nav) - SUM(h.cost_amount))
             / NULLIF(SUM(h.cost_amount), 0) * 100, 2)                    AS return_pct,
       ROUND(AVG((TRUNC(SYSDATE) - h.purchase_date)), 0)              AS avg_holding_days,
       MAX((TRUNC(SYSDATE) - h.purchase_date))                        AS max_holding_days,
       CASE WHEN SUM(h.shares * l.nav) >= SUM(h.cost_amount) THEN 'profit' ELSE 'loss' END AS pnl_flag
FROM holdings h
JOIN latest l ON h.fund_id = l.fund_id AND l.rn = 1
JOIN fin_customers c ON h.cust_id = c.cust_id
GROUP BY c.cust_id, c.cust_name
ORDER BY unrealized_pnl DESC
FETCH FIRST 500 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [187] 定投·收益模拟 | 金融 | 基金定投成本与收益分析（按持有期）
-- ------------------------------------------------------------------------------
WITH nav AS (
    SELECT fund_id, nav_date, nav,
           ROW_NUMBER() OVER (PARTITION BY fund_id ORDER BY nav_date) AS rn
    FROM fund_nav
    WHERE nav_date >= (TRUNC(SYSDATE) - 730)
),
sip AS (
    SELECT fund_id,
           COUNT(*)                    AS periods,
           AVG(nav)                    AS avg_nav,
           MIN(nav)                    AS min_nav,
           MAX(nav)                    AS max_nav,
           SUM(1 / NULLIF(nav, 0))     AS inv_nav_sum
    FROM nav GROUP BY fund_id
)
SELECT f.fund_name, f.fund_type,
       s.periods,
       ROUND(s.avg_nav, 4)                                       AS arithmetic_avg_nav,
       ROUND(s.periods / NULLIF(s.inv_nav_sum, 0), 4)            AS harmonic_avg_nav,
       ROUND((s.avg_nav - s.periods / NULLIF(s.inv_nav_sum, 0))
             / NULLIF(s.periods / NULLIF(s.inv_nav_sum, 0), 0) * 100, 2) AS sip_advantage_pct,
       ROUND(s.min_nav, 4) AS min_nav,
       ROUND(s.max_nav, 4) AS max_nav,
       ROUND((s.periods / NULLIF(s.inv_nav_sum, 0) - s.min_nav)
             / NULLIF(s.max_nav - s.min_nav, 0) * 100, 2)        AS cost_position_pct
FROM sip s
JOIN fund_products f ON s.fund_id = f.fund_id
ORDER BY sip_advantage_pct DESC
FETCH FIRST 200 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [188] 申赎·资金流 | 金融 | 基金申赎资金流与净流入分析
-- ------------------------------------------------------------------------------
WITH h AS (
    SELECT fund_id, cust_id, purchase_date, cost_amount, shares,
           COUNT(*) OVER (PARTITION BY fund_id) AS holder_cnt
    FROM holdings
),
monthly AS (
    SELECT fund_id, TO_CHAR(purchase_date, 'YYYY-MM') AS ym,
           COUNT(*)          AS subscribe_cnt,
           SUM(cost_amount)  AS subscribe_amt
    FROM h GROUP BY fund_id, TO_CHAR(purchase_date, 'YYYY-MM')
)
SELECT f.fund_name, f.fund_type, m.ym,
       m.subscribe_cnt,
       ROUND(m.subscribe_amt, 2)                                      AS subscribe_amt,
       LAG(m.subscribe_amt) OVER (PARTITION BY m.fund_id ORDER BY m.ym) AS prev_amt,
       ROUND((m.subscribe_amt - LAG(m.subscribe_amt) OVER (PARTITION BY m.fund_id ORDER BY m.ym))
             / NULLIF(LAG(m.subscribe_amt) OVER (PARTITION BY m.fund_id ORDER BY m.ym), 0) * 100, 2) AS mom_pct,
       ROUND(SUM(m.subscribe_amt) OVER (PARTITION BY m.fund_id ORDER BY m.ym
             ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW), 2)    AS cum_inflow,
       MAX(h2.holder_cnt)                                             AS current_holders
FROM monthly m
JOIN fund_products f ON m.fund_id = f.fund_id
JOIN h h2 ON m.fund_id = h2.fund_id
GROUP BY f.fund_name, f.fund_type, m.ym, m.subscribe_cnt, m.subscribe_amt, m.fund_id
ORDER BY f.fund_name, m.ym DESC
FETCH FIRST 300 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [189] 经理·业绩评价 | 金融 | 基金经理管理规模与业绩评价
-- ------------------------------------------------------------------------------
WITH mgr AS (
    SELECT f.manager_id, f.fund_id, f.fund_name, f.fund_type,
           COUNT(h.hold_id)                     AS holder_cnt,
           SUM(h.cost_amount)                   AS aum
    FROM fund_products f
    LEFT JOIN holdings h ON f.fund_id = h.fund_id
    GROUP BY f.manager_id, f.fund_id, f.fund_name, f.fund_type
),
perf AS (
    SELECT n.fund_id,
           MAX(n.nav) / NULLIF(MIN(n.nav), 0) - 1 AS nav_growth
    FROM fund_nav n
    WHERE n.nav_date >= (TRUNC(SYSDATE) - 365)
    GROUP BY n.fund_id
)
SELECT m.manager_id,
       COUNT(*)                                 AS fund_cnt,
       SUM(m.holder_cnt)                        AS total_holders,
       ROUND(SUM(m.aum), 2)                     AS total_aum,
       ROUND(AVG(p.nav_growth) * 100, 2)        AS avg_nav_growth_pct,
       ROUND(SUM(m.aum * p.nav_growth) / NULLIF(SUM(m.aum), 0) * 100, 2) AS aum_weighted_return_pct,
       RANK() OVER (ORDER BY SUM(m.aum * p.nav_growth) / NULLIF(SUM(m.aum), 0) DESC) AS manager_rank,
       ROUND(SUM(m.aum) / NULLIF(SUM(SUM(m.aum)) OVER (), 0) * 100, 2) AS aum_share_pct
FROM mgr m
LEFT JOIN perf p ON m.fund_id = p.fund_id
GROUP BY m.manager_id
ORDER BY total_aum DESC;

-- ------------------------------------------------------------------------------
-- [190] 对比·基金类型 | 金融 | 基金类型风险收益特征对比
-- ------------------------------------------------------------------------------
WITH r AS (
    SELECT n.fund_id,
           (MAX(n.nav) - MIN(n.nav)) / NULLIF(MIN(n.nav), 0) AS nav_range,
           AVG(n.nav)                                        AS avg_nav,
           MAX(n.nav)                                        AS max_nav,
           MIN(n.nav)                                        AS min_nav,
           COUNT(*)                                          AS nav_days
    FROM fund_nav n
    WHERE n.nav_date >= (TRUNC(SYSDATE) - 365)
    GROUP BY n.fund_id
)
SELECT f.fund_type, f.risk_level,
       COUNT(*)                                        AS fund_cnt,
       ROUND(AVG(r.nav_range) * 100, 2)                AS avg_nav_range_pct,
       ROUND(AVG(r.avg_nav), 4)                        AS avg_nav,
       ROUND(MAX(r.max_nav), 4)                        AS peak_nav,
       ROUND(MIN(r.min_nav), 4)                        AS trough_nav,
       ROUND(AVG(r.nav_days), 0)                       AS avg_nav_days,
       ROUND(AVG(r.nav_range) * 100 - MIN(r.nav_range) * 100, 2) AS range_dispersion
FROM r
JOIN fund_products f ON r.fund_id = f.fund_id
GROUP BY f.fund_type, f.risk_level
ORDER BY avg_nav_range_pct DESC;

-- ------------------------------------------------------------------------------
-- [191] 集中·持仓分析 | 金融 | 客户持仓集中度与分散度评估
-- ------------------------------------------------------------------------------
WITH hv AS (
    SELECT h.cust_id, h.fund_id,
           h.shares * l.nav AS market_value
    FROM holdings h
    JOIN (SELECT fund_id, nav FROM fund_nav n1
          WHERE n1.nav_date = (SELECT MAX(nav_date) FROM fund_nav n2 WHERE n2.fund_id = n1.fund_id)) l
      ON h.fund_id = l.fund_id
),
agg AS (
    SELECT cust_id,
           COUNT(*)                                              AS fund_cnt,
           SUM(market_value)                                     AS total_value,
           MAX(market_value)                                     AS max_position,
           MAX(market_value) / NULLIF(SUM(market_value), 0)      AS top1_weight
    FROM hv GROUP BY cust_id
)
SELECT CASE WHEN top1_weight >= 0.8 THEN 'highly_concentrated'
            WHEN top1_weight >= 0.5 THEN 'concentrated'
            WHEN fund_cnt >= 5      THEN 'diversified'
            ELSE 'moderate' END AS diversification_level,
       COUNT(*)                                    AS cust_cnt,
       ROUND(AVG(total_value), 2)                  AS avg_portfolio_value,
       ROUND(AVG(fund_cnt), 2)                     AS avg_fund_cnt,
       ROUND(AVG(top1_weight) * 100, 2)            AS avg_top1_weight_pct,
       ROUND(SUM(total_value) / NULLIF(SUM(SUM(total_value)) OVER (), 0) * 100, 2) AS value_share_pct
FROM agg
GROUP BY CASE WHEN top1_weight >= 0.8 THEN 'highly_concentrated'
              WHEN top1_weight >= 0.5 THEN 'concentrated'
              WHEN fund_cnt >= 5      THEN 'diversified'
              ELSE 'moderate' END
ORDER BY avg_top1_weight_pct ASC;

-- ------------------------------------------------------------------------------
-- [192] 回本·持仓分析 | 金融 | 客户持仓回本分析与套牢识别
-- ------------------------------------------------------------------------------
WITH cur AS (
    SELECT fund_id, nav AS cur_nav
    FROM fund_nav n1
    WHERE n1.nav_date = (SELECT MAX(nav_date) FROM fund_nav n2 WHERE n2.fund_id = n1.fund_id)
),
pos AS (
    SELECT h.cust_id, h.fund_id, h.cost_amount, h.shares, h.purchase_date,
           c.cur_nav,
           h.shares * c.cur_nav          AS market_value,
           h.cost_amount / NULLIF(h.shares, 0) AS avg_cost_nav
    FROM holdings h
    JOIN cur c ON h.fund_id = c.fund_id
)
SELECT f.fund_name,
       COUNT(*)                                                          AS position_cnt,
       ROUND(SUM(market_value), 2)                                       AS market_value,
       ROUND(SUM(cost_amount), 2)                                        AS cost_amount,
       ROUND(SUM(market_value) - SUM(cost_amount), 2)                    AS pnl,
       ROUND(AVG((cur_nav - avg_cost_nav) / NULLIF(avg_cost_nav, 0)) * 100, 2) AS avg_gap_pct,
       SUM(CASE WHEN market_value < cost_amount * 0.9 THEN 1 ELSE 0 END) AS deep_loss_cnt,
       SUM(CASE WHEN market_value < cost_amount * 0.7 THEN 1 ELSE 0 END) AS severe_loss_cnt,
       ROUND(SUM(CASE WHEN market_value < cost_amount THEN 1 ELSE 0 END)
             / NULLIF(COUNT(*), 0) * 100, 2)                             AS loss_position_pct,
       ROUND(AVG((TRUNC(SYSDATE) - purchase_date)), 0)               AS avg_holding_days
FROM pos
JOIN fund_products f ON pos.fund_id = f.fund_id
GROUP BY f.fund_name
ORDER BY pnl ASC
FETCH FIRST 200 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [193] 归因·收益分解 | 金融 | 客户组合收益归因（按基金类型）
-- ------------------------------------------------------------------------------
WITH hv AS (
    SELECT h.cust_id, h.fund_id, f.fund_type,
           h.cost_amount,
           h.shares * l.nav AS market_value
    FROM holdings h
    JOIN fund_products f ON h.fund_id = f.fund_id
    JOIN (SELECT fund_id, nav FROM fund_nav n1
          WHERE n1.nav_date = (SELECT MAX(nav_date) FROM fund_nav n2 WHERE n2.fund_id = n1.fund_id)) l
      ON h.fund_id = l.fund_id
),
agg AS (
    SELECT cust_id, fund_type,
           SUM(cost_amount)                                    AS cost,
           SUM(market_value)                                   AS value,
           SUM(market_value) - SUM(cost_amount)                AS pnl,
           SUM(SUM(market_value)) OVER (PARTITION BY cust_id)  AS cust_total_value
    FROM hv GROUP BY cust_id, fund_type
)
SELECT fund_type,
       COUNT(DISTINCT cust_id)                                     AS cust_cnt,
       ROUND(SUM(cost), 2)                                         AS total_cost,
       ROUND(SUM(value), 2)                                        AS total_value,
       ROUND(SUM(pnl), 2)                                          AS total_pnl,
       ROUND(SUM(pnl) / NULLIF(SUM(cost), 0) * 100, 2)             AS return_pct,
       ROUND(SUM(pnl) / NULLIF(SUM(SUM(pnl)) OVER (), 0) * 100, 2) AS pnl_contribution_pct,
       ROUND(AVG(value / NULLIF(cust_total_value, 0)) * 100, 2)    AS avg_allocation_pct
FROM agg
GROUP BY fund_type
ORDER BY total_pnl DESC;

-- ------------------------------------------------------------------------------
-- [194] 匹配·风险偏好 | 金融 | 客户风险偏好与持仓风险匹配度
-- ------------------------------------------------------------------------------
WITH cust_risk AS (
    SELECT cust_id, risk_level,
           CASE risk_level WHEN 'low'    THEN 1
                           WHEN 'medium' THEN 2
                           WHEN 'high'   THEN 3
                           ELSE 2 END AS risk_num
    FROM fin_customers
),
port_risk AS (
    SELECT h.cust_id,
           SUM(CASE f.risk_level WHEN 'low'    THEN 1
                                 WHEN 'medium' THEN 2
                                 WHEN 'high'   THEN 3
                                 ELSE 2 END * h.cost_amount)
             / NULLIF(SUM(h.cost_amount), 0) AS portfolio_risk_num,
           SUM(h.cost_amount) AS total_cost
    FROM holdings h
    JOIN fund_products f ON h.fund_id = f.fund_id
    GROUP BY h.cust_id
)
SELECT cr.risk_level AS declared_risk,
       COUNT(*)                                                    AS cust_cnt,
       ROUND(AVG(pr.portfolio_risk_num), 2)                        AS avg_portfolio_risk,
       SUM(CASE WHEN pr.portfolio_risk_num > cr.risk_num + 0.5 THEN 1 ELSE 0 END) AS over_risk_cnt,
       SUM(CASE WHEN pr.portfolio_risk_num < cr.risk_num - 0.5 THEN 1 ELSE 0 END) AS under_risk_cnt,
       ROUND(SUM(CASE WHEN pr.portfolio_risk_num > cr.risk_num + 0.5 THEN 1 ELSE 0 END)
             / NULLIF(COUNT(*), 0) * 100, 2)                       AS mismatch_pct,
       ROUND(AVG(pr.total_cost), 2)                                AS avg_investment,
       CASE WHEN SUM(CASE WHEN pr.portfolio_risk_num > cr.risk_num + 0.5 THEN 1 ELSE 0 END)
                 / NULLIF(COUNT(*), 0) > 0.3 THEN 'suitability_issue'
            ELSE 'compliant' END AS suitability_flag
FROM cust_risk cr
JOIN port_risk pr ON cr.cust_id = pr.cust_id
GROUP BY cr.risk_level, cr.risk_num
ORDER BY cr.risk_num;

-- ------------------------------------------------------------------------------
-- [195] 到期·理财收益 | 金融 | 存款到期收益与利息支出测算
-- ------------------------------------------------------------------------------
WITH dep AS (
    SELECT d.deposit_id, d.cust_id, d.amount, d.rate, d.start_date, d.maturity_date,
           (d.maturity_date - d.start_date)  AS days_held,
           MONTHS_BETWEEN(d.maturity_date, d.start_date) AS months_held
    FROM deposits d
)
SELECT CASE WHEN months_held <= 3  THEN 'demand_like'
            WHEN months_held <= 12 THEN 'short_term'
            WHEN months_held <= 36 THEN 'medium_term'
            ELSE 'long_term' END AS term_type,
       COUNT(*)                                                        AS dep_cnt,
       ROUND(SUM(amount), 2)                                           AS principal,
       ROUND(SUM(amount * rate * days_held / 365), 2)                  AS interest_expense,
       ROUND(SUM(amount * rate * days_held / 365)
             / NULLIF(SUM(amount), 0) * 100, 3)                        AS effective_rate_pct,
       ROUND(AVG(days_held), 0)                                        AS avg_days,
       SUM(CASE WHEN maturity_date <= TRUNC(SYSDATE) THEN 1 ELSE 0 END)     AS matured_cnt,
       ROUND(SUM(CASE WHEN maturity_date <= TRUNC(SYSDATE) THEN amount * rate * days_held / 365 ELSE 0 END), 2) AS matured_interest
FROM dep
GROUP BY CASE WHEN months_held <= 3  THEN 'demand_like'
              WHEN months_held <= 12 THEN 'short_term'
              WHEN months_held <= 36 THEN 'medium_term'
              ELSE 'long_term' END
ORDER BY principal DESC;

-- ------------------------------------------------------------------------------
-- [196] 趋势·利润表 | 金融 | 分支行利润表趋势与环比分析
-- ------------------------------------------------------------------------------
SELECT b.branch_name, r.period,
       ROUND(r.revenue, 2) AS revenue,
       ROUND(r.cost, 2)    AS cost,
       ROUND(r.profit, 2)  AS profit,
       ROUND(r.profit / NULLIF(r.revenue, 0) * 100, 2)                        AS profit_margin_pct,
       ROUND(r.revenue - LAG(r.revenue) OVER (PARTITION BY b.branch_name ORDER BY r.period), 2) AS revenue_delta,
       ROUND((r.revenue - LAG(r.revenue) OVER (PARTITION BY b.branch_name ORDER BY r.period))
             / NULLIF(LAG(r.revenue) OVER (PARTITION BY b.branch_name ORDER BY r.period), 0) * 100, 2) AS revenue_mom_pct,
       ROUND(SUM(r.profit) OVER (PARTITION BY b.branch_name ORDER BY r.period
             ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW), 2)           AS cum_profit,
       ROUND(AVG(r.profit) OVER (PARTITION BY b.branch_name ORDER BY r.period
             ROWS BETWEEN 2 PRECEDING AND CURRENT ROW), 2)                   AS profit_ma3
FROM fin_reports r
JOIN branches b ON r.branch_id = b.branch_id
ORDER BY b.branch_name, r.period;

-- ------------------------------------------------------------------------------
-- [197] 结构·资产负债 | 金融 | 资产负债结构与财务杠杆分析
-- ------------------------------------------------------------------------------
SELECT b.region, r.period,
       ROUND(SUM(r.asset), 2)                                      AS total_asset,
       ROUND(SUM(r.liability), 2)                                  AS total_liability,
       ROUND(SUM(r.asset) - SUM(r.liability), 2)                   AS net_asset,
       ROUND(SUM(r.liability) / NULLIF(SUM(r.asset), 0) * 100, 2)  AS liability_ratio_pct,
       ROUND(SUM(r.asset) / NULLIF(SUM(r.liability), 0), 2)        AS asset_liability_ratio,
       ROUND(SUM(r.deposit_amt), 2)                                AS deposit_amt,
       ROUND(SUM(r.loan_amt), 2)                                   AS loan_amt,
       ROUND(SUM(r.loan_amt) / NULLIF(SUM(r.deposit_amt), 0) * 100, 2) AS ldr_pct,
       ROUND(SUM(r.profit) / NULLIF(SUM(r.asset), 0) * 100, 3)     AS roa_pct
FROM fin_reports r
JOIN branches b ON r.branch_id = b.branch_id
GROUP BY b.region, r.period
ORDER BY b.region, r.period DESC;

-- ------------------------------------------------------------------------------
-- [198] 预算·执行分析 | 金融 | 预算执行率与偏差分析
-- ------------------------------------------------------------------------------
WITH bud AS (
    SELECT branch_id, period,
           SUM(revenue) AS actual_revenue,
           SUM(cost)    AS actual_cost,
           SUM(profit)  AS actual_profit
    FROM fin_reports GROUP BY branch_id, period
),
plan AS (
    SELECT branch_id, period,
           AVG(revenue) OVER (PARTITION BY branch_id) * 1.1 AS budget_revenue,
           AVG(cost)    OVER (PARTITION BY branch_id) * 0.95 AS budget_cost
    FROM fin_reports
)
SELECT b.branch_name, bd.period,
       ROUND(bd.actual_revenue, 2)                                        AS actual_revenue,
       ROUND(MAX(p.budget_revenue), 2)                                    AS budget_revenue,
       ROUND(bd.actual_revenue / NULLIF(MAX(p.budget_revenue), 0) * 100, 2) AS revenue_execution_pct,
       ROUND(bd.actual_cost, 2)                                           AS actual_cost,
       ROUND(MAX(p.budget_cost), 2)                                       AS budget_cost,
       ROUND(bd.actual_cost / NULLIF(MAX(p.budget_cost), 0) * 100, 2)     AS cost_execution_pct,
       ROUND(bd.actual_revenue - MAX(p.budget_revenue), 2)                AS revenue_variance,
       CASE WHEN bd.actual_revenue / NULLIF(MAX(p.budget_revenue), 0) >= 1 THEN 'on_track'
            WHEN bd.actual_revenue / NULLIF(MAX(p.budget_revenue), 0) >= 0.9 THEN 'slight_gap'
            ELSE 'significant_gap' END AS execution_status
FROM bud bd
JOIN plan p  ON bd.branch_id = p.branch_id AND bd.period = p.period
JOIN branches b ON bd.branch_id = b.branch_id
GROUP BY b.branch_name, bd.period, bd.actual_revenue, bd.actual_cost
ORDER BY b.branch_name, bd.period DESC;

-- ------------------------------------------------------------------------------
-- [199] 同比·财务对比 | 金融 | 财务指标同比（YoY）对比分析
-- ------------------------------------------------------------------------------
WITH y AS (
    SELECT branch_id,
           SUBSTR(period, 1, 4) AS yr,
           SUM(revenue) AS revenue,
           SUM(cost)    AS cost,
           SUM(profit)  AS profit
    FROM fin_reports
    GROUP BY branch_id, SUBSTR(period, 1, 4)
)
SELECT b.branch_name, y.yr,
       ROUND(y.revenue, 2) AS revenue,
       ROUND(LAG(y.revenue) OVER (PARTITION BY y.branch_id ORDER BY y.yr), 2) AS prev_revenue,
       ROUND((y.revenue - LAG(y.revenue) OVER (PARTITION BY y.branch_id ORDER BY y.yr))
             / NULLIF(LAG(y.revenue) OVER (PARTITION BY y.branch_id ORDER BY y.yr), 0) * 100, 2) AS revenue_yoy_pct,
       ROUND(y.profit, 2)  AS profit,
       ROUND((y.profit - LAG(y.profit) OVER (PARTITION BY y.branch_id ORDER BY y.yr))
             / NULLIF(LAG(y.profit) OVER (PARTITION BY y.branch_id ORDER BY y.yr), 0) * 100, 2)  AS profit_yoy_pct,
       ROUND(y.profit / NULLIF(y.revenue, 0) * 100, 2) AS margin_pct,
       ROUND(y.profit / NULLIF(y.revenue, 0) * 100
             - LAG(y.profit) OVER (PARTITION BY y.branch_id ORDER BY y.yr)
               / NULLIF(LAG(y.revenue) OVER (PARTITION BY y.branch_id ORDER BY y.yr), 0) * 100, 2) AS margin_delta
FROM y
JOIN branches b ON y.branch_id = b.branch_id
ORDER BY b.branch_name, y.yr;

-- ------------------------------------------------------------------------------
-- [200] 杜邦·盈利分解 | 金融 | 杜邦分析（利润率 × 资产周转 × 杠杆）
-- ------------------------------------------------------------------------------
WITH f AS (
    SELECT r.branch_id, r.period,
           SUM(r.profit)    AS profit,
           SUM(r.revenue)   AS revenue,
           SUM(r.asset)     AS asset,
           SUM(r.liability) AS liability
    FROM fin_reports r
    GROUP BY r.branch_id, r.period
)
SELECT b.branch_name, f.period,
       ROUND(f.profit / NULLIF(f.revenue, 0) * 100, 2)              AS net_margin_pct,
       ROUND(f.revenue / NULLIF(f.asset, 0), 3)                     AS asset_turnover,
       ROUND(f.asset / NULLIF(f.asset - f.liability, 0), 2)         AS equity_multiplier,
       ROUND(f.profit / NULLIF(f.asset - f.liability, 0) * 100, 2)  AS roe_pct,
       ROUND(f.profit / NULLIF(f.asset, 0) * 100, 3)                AS roa_pct,
       RANK() OVER (PARTITION BY f.period ORDER BY f.profit / NULLIF(f.asset - f.liability, 0) DESC) AS roe_rank,
       CASE WHEN f.profit / NULLIF(f.asset - f.liability, 0) >= 0.15 THEN 'excellent'
            WHEN f.profit / NULLIF(f.asset - f.liability, 0) >= 0.08 THEN 'good'
            ELSE 'below_par' END AS roe_grade
FROM f
JOIN branches b ON f.branch_id = b.branch_id
ORDER BY f.period DESC, roe_pct DESC;

-- ------------------------------------------------------------------------------
-- [201] 汇率·变动影响 | 金融 | 汇率变动趋势与波动分析
-- ------------------------------------------------------------------------------
WITH r AS (
    SELECT currency, rate_date, rate,
           LAG(rate) OVER (PARTITION BY currency ORDER BY rate_date) AS prev_rate,
           AVG(rate) OVER (PARTITION BY currency ORDER BY rate_date
                ROWS BETWEEN 30 PRECEDING AND CURRENT ROW) AS ma30
    FROM fx_rates
    WHERE rate_date >= (TRUNC(SYSDATE) - 365)
)
SELECT currency,
       COUNT(*)                                          AS quote_days,
       ROUND(MAX(rate), 6)                               AS max_rate,
       ROUND(MIN(rate), 6)                               AS min_rate,
       ROUND(AVG(rate), 6)                               AS avg_rate,
       ROUND((MAX(rate) - MIN(rate)) / NULLIF(MIN(rate), 0) * 100, 2) AS range_pct,
       ROUND(MAX(rate) - MIN(rate), 6)                   AS absolute_range,
       ROUND(AVG(ABS((rate - prev_rate) / NULLIF(prev_rate, 0))) * 100, 4) AS avg_daily_move_pct,
       CASE WHEN (MAX(rate) - MIN(rate)) / NULLIF(MIN(rate), 0) > 0.1 THEN 'high_volatility'
            WHEN (MAX(rate) - MIN(rate)) / NULLIF(MIN(rate), 0) > 0.05 THEN 'moderate'
            ELSE 'stable' END AS volatility_flag
FROM r
GROUP BY currency
ORDER BY range_pct DESC;

-- ------------------------------------------------------------------------------
-- [202] 敞口·外汇风险 | 金融 | 外汇敞口估算与汇率敏感性
-- ------------------------------------------------------------------------------
WITH latest AS (
    SELECT currency, rate
    FROM fx_rates f1
    WHERE f1.rate_date = (SELECT MAX(rate_date) FROM fx_rates f2 WHERE f2.currency = f1.currency)
),
expo AS (
    SELECT a.currency AS currency_code,
           SUM(t.amount) AS exposure_amt
    FROM transactions t
    JOIN accounts a ON t.account_id = a.account_id
    WHERE t.txn_date >= (TRUNC(SYSDATE) - 90)
      AND a.currency IN (SELECT currency FROM fx_rates)
    GROUP BY a.currency
)
SELECT e.currency_code,
       ROUND(e.exposure_amt, 2)                          AS exposure_amt,
       ROUND(l.rate, 6)                                  AS current_rate,
       ROUND(e.exposure_amt * l.rate, 2)                 AS base_currency_value,
       ROUND(e.exposure_amt * l.rate * 0.05, 2)          AS sensitivity_5pct,
       ROUND(e.exposure_amt * l.rate * 0.10, 2)          AS sensitivity_10pct,
       ROUND(e.exposure_amt * l.rate
             / NULLIF(SUM(e.exposure_amt * l.rate) OVER (), 0) * 100, 2) AS exposure_share_pct,
       CASE WHEN e.exposure_amt * l.rate * 0.10 > 1000000 THEN 'material_exposure'
            WHEN e.exposure_amt * l.rate * 0.10 > 100000  THEN 'monitor'
            ELSE 'immaterial' END AS exposure_flag
FROM expo e
JOIN latest l ON e.currency_code = l.currency
ORDER BY base_currency_value DESC;

-- ------------------------------------------------------------------------------
-- [203] 成本·中心分析 | 金融 | 成本中心费用分析与效率评估
-- ------------------------------------------------------------------------------
WITH c AS (
    SELECT r.branch_id, r.period,
           SUM(r.cost)    AS total_cost,
           SUM(r.revenue) AS total_revenue,
           SUM(r.profit)  AS total_profit
    FROM fin_reports r
    GROUP BY r.branch_id, r.period
)
SELECT b.branch_name, b.region,
       ROUND(AVG(c.total_cost), 2)                                       AS avg_cost,
       ROUND(AVG(c.total_revenue), 2)                                    AS avg_revenue,
       ROUND(AVG(c.total_cost) / NULLIF(AVG(c.total_revenue), 0) * 100, 2) AS cost_ratio_pct,
       ROUND(AVG(c.total_cost) - MIN(c.total_cost), 2)                   AS cost_variance,
       ROUND(STDDEV_SAMP(c.total_cost) / NULLIF(AVG(c.total_cost), 0) * 100, 2) AS cost_volatility_pct,
       RANK() OVER (ORDER BY AVG(c.total_cost) / NULLIF(AVG(c.total_revenue), 0) DESC) AS inefficiency_rank,
       CASE WHEN AVG(c.total_cost) / NULLIF(AVG(c.total_revenue), 0) > 0.8 THEN 'cost_control_needed'
            WHEN AVG(c.total_cost) / NULLIF(AVG(c.total_revenue), 0) > 0.6 THEN 'watch'
            ELSE 'efficient' END AS efficiency_grade
FROM c
JOIN branches b ON c.branch_id = b.branch_id
GROUP BY b.branch_name, b.region
ORDER BY cost_ratio_pct DESC;

-- ------------------------------------------------------------------------------
-- [204] 人效·人均产出 | 金融 | 分支行人均产出与人员效率
-- ------------------------------------------------------------------------------
WITH br AS (
    SELECT r.branch_id, SUM(r.revenue) AS revenue, SUM(r.profit) AS profit
    FROM fin_reports r
    WHERE r.period >= TO_CHAR((TRUNC(SYSDATE) - 365), 'YYYY-MM')
    GROUP BY r.branch_id
),
emp AS (
    SELECT branch_id, COUNT(*) AS emp_cnt
    FROM employees
    WHERE status = 'active'
    GROUP BY branch_id
)
SELECT b.branch_name, b.city, b.region,
       COALESCE(e.emp_cnt, 0)                                   AS emp_cnt,
       ROUND(br.revenue, 2)                                     AS revenue,
       ROUND(br.profit, 2)                                      AS profit,
       ROUND(br.revenue / NULLIF(e.emp_cnt, 0), 2)              AS revenue_per_capita,
       ROUND(br.profit / NULLIF(e.emp_cnt, 0), 2)               AS profit_per_capita,
       RANK() OVER (ORDER BY br.profit / NULLIF(e.emp_cnt, 0) DESC) AS productivity_rank,
       ROUND(br.profit / NULLIF(e.emp_cnt, 0)
             / NULLIF(AVG(br.profit / NULLIF(e.emp_cnt, 0)) OVER (), 0), 2) AS vs_avg_multiple
FROM br
JOIN branches b ON br.branch_id = b.branch_id
LEFT JOIN emp e ON br.branch_id = e.branch_id
ORDER BY profit_per_capita DESC;

-- ------------------------------------------------------------------------------
-- [205] 结构·收入分析 | 金融 | 收入结构分析与中收占比
-- ------------------------------------------------------------------------------
WITH rev AS (
    SELECT r.branch_id,
           SUM(r.revenue) AS total_revenue,
           SUM(CASE WHEN r.period >= TO_CHAR(TRUNC(TRUNC(SYSDATE), 'MM'), 'YYYY-MM')
                    THEN r.revenue ELSE 0 END) AS current_month_revenue,
           SUM(r.profit)  AS total_profit
    FROM fin_reports r GROUP BY r.branch_id
),
loan_inc AS (
    SELECT branch_id, SUM(loan_amount * interest_rate / 100) AS interest_income
    FROM loans WHERE status IN ('active', 'overdue') GROUP BY branch_id
)
SELECT b.branch_name, b.region,
       ROUND(rr.total_revenue, 2)                                         AS total_revenue,
       ROUND(COALESCE(li.interest_income, 0), 2)                          AS interest_income,
       ROUND(rr.total_revenue - COALESCE(li.interest_income, 0), 2)       AS non_interest_income,
       ROUND(COALESCE(li.interest_income, 0)
             / NULLIF(rr.total_revenue, 0) * 100, 2)                      AS interest_income_pct,
       ROUND((rr.total_revenue - COALESCE(li.interest_income, 0))
             / NULLIF(rr.total_revenue, 0) * 100, 2)                      AS non_interest_pct,
       ROUND(rr.total_profit / NULLIF(rr.total_revenue, 0) * 100, 2)      AS margin_pct,
       ROUND(rr.current_month_revenue, 2)                                 AS current_month_revenue
FROM rev rr
JOIN branches b ON rr.branch_id = b.branch_id
LEFT JOIN loan_inc li ON rr.branch_id = li.branch_id
ORDER BY total_revenue DESC;

-- ------------------------------------------------------------------------------
-- [206] 质量·增长分析 | 金融 | 收入增长质量与可持续性评估
-- ------------------------------------------------------------------------------
WITH y AS (
    SELECT branch_id, period,
           SUM(revenue) AS revenue, SUM(profit) AS profit, SUM(cost) AS cost
    FROM fin_reports GROUP BY branch_id, period
),
g AS (
    SELECT branch_id, period, revenue, profit, cost,
           LAG(revenue) OVER (PARTITION BY branch_id ORDER BY period) AS prev_revenue,
           LAG(profit)  OVER (PARTITION BY branch_id ORDER BY period) AS prev_profit
    FROM y
)
SELECT b.branch_name,
       ROUND(AVG((g.revenue - g.prev_revenue) / NULLIF(g.prev_revenue, 0)) * 100, 2) AS avg_revenue_growth_pct,
       ROUND(AVG((g.profit - g.prev_profit) / NULLIF(g.prev_profit, 0)) * 100, 2)    AS avg_profit_growth_pct,
       ROUND(AVG((g.revenue - g.prev_revenue) / NULLIF(g.prev_revenue, 0))
             - AVG((g.profit - g.prev_profit) / NULLIF(g.prev_profit, 0)) * 100, 2)  AS growth_quality_gap,
       SUM(CASE WHEN g.revenue > g.prev_revenue AND g.profit > g.prev_profit THEN 1 ELSE 0 END) AS both_growth_periods,
       SUM(CASE WHEN g.revenue > g.prev_revenue AND g.profit <= g.prev_profit THEN 1 ELSE 0 END) AS unhealthy_growth_periods,
       COUNT(*) AS total_periods,
       CASE WHEN SUM(CASE WHEN g.revenue > g.prev_revenue AND g.profit <= g.prev_profit THEN 1 ELSE 0 END)
                 > COUNT(*) * 0.3 THEN 'unsustainable_growth'
            ELSE 'healthy' END AS growth_quality
FROM g
JOIN branches b ON g.branch_id = b.branch_id
WHERE g.prev_revenue IS NOT NULL
GROUP BY b.branch_name
ORDER BY avg_profit_growth_pct DESC;

-- ------------------------------------------------------------------------------
-- [207] 综合·经营分析 | 金融 | 分支行综合经营分析（规模+质量+效益）
-- ------------------------------------------------------------------------------
WITH m AS (
    SELECT r.branch_id,
           SUM(r.revenue)                                          AS revenue,
           SUM(r.profit)                                           AS profit,
           SUM(r.deposit_amt)                                      AS deposit,
           SUM(r.loan_amt)                                         AS loan,
           SUM(r.asset)                                            AS asset
    FROM fin_reports r
    WHERE r.period >= TO_CHAR((TRUNC(SYSDATE) - 365), 'YYYY-MM')
    GROUP BY r.branch_id
),
q AS (
    SELECT branch_id,
           SUM(loan_amount)                                                     AS loan_amt,
           SUM(CASE WHEN status IN ('overdue', 'default') THEN loan_amount ELSE 0 END) AS bad_amt
    FROM loans GROUP BY branch_id
)
SELECT b.branch_name, b.region,
       ROUND(m.revenue, 2)                                            AS revenue,
       ROUND(m.profit, 2)                                             AS profit,
       ROUND(m.deposit, 2)                                            AS deposit,
       ROUND(m.loan, 2)                                               AS loan,
       ROUND(COALESCE(q.bad_amt, 0) / NULLIF(COALESCE(q.loan_amt, 0), 0) * 100, 2) AS npl_pct,
       ROUND(m.profit / NULLIF(m.revenue, 0) * 100, 2)                AS margin_pct,
       ROUND(m.loan / NULLIF(m.deposit, 0) * 100, 2)                  AS ldr_pct,
       RANK() OVER (ORDER BY m.profit DESC)                           AS profit_rank,
       RANK() OVER (ORDER BY COALESCE(q.bad_amt, 0) / NULLIF(COALESCE(q.loan_amt, 0), 0) ASC) AS quality_rank,
       ROUND((RANK() OVER (ORDER BY m.profit DESC)
              + RANK() OVER (ORDER BY COALESCE(q.bad_amt, 0) / NULLIF(COALESCE(q.loan_amt, 0), 0) ASC)) / 2.0, 1) AS composite_score
FROM m
JOIN branches b ON m.branch_id = b.branch_id
LEFT JOIN q ON m.branch_id = q.branch_id
ORDER BY composite_score;

-- ------------------------------------------------------------------------------
-- [208] 贡献·客户综合 | 金融 | 客户综合贡献度与价值评级
-- ------------------------------------------------------------------------------
WITH contrib AS (
    SELECT l.cust_id,
           SUM(l.loan_amount * l.interest_rate / 100) AS loan_income
    FROM loans l WHERE l.status IN ('active', 'overdue') GROUP BY l.cust_id
),
dep_profit AS (
    SELECT d.cust_id, SUM(d.amount * (0.03 - d.rate)) AS deposit_income
    FROM deposits d WHERE d.status = 'active' GROUP BY d.cust_id
),
card_inc AS (
    SELECT c.cust_id, SUM(t.amount * 0.006) AS card_fee_income
    FROM card_txns t JOIN cards c ON t.card_id = c.card_id
    WHERE t.txn_date >= (TRUNC(SYSDATE) - 365)
    GROUP BY c.cust_id
),
cost AS (
    SELECT a.cust_id, COUNT(*) * 50 AS service_cost
    FROM accounts a GROUP BY a.cust_id
)
SELECT c.cust_id, c.cust_name, c.cust_type,
       ROUND(COALESCE(ct.loan_income, 0), 2)    AS loan_income,
       ROUND(COALESCE(dp.deposit_income, 0), 2) AS deposit_income,
       ROUND(COALESCE(ci.card_fee_income, 0), 2) AS card_income,
       ROUND(COALESCE(ct.loan_income, 0) + COALESCE(dp.deposit_income, 0)
             + COALESCE(ci.card_fee_income, 0), 2)                    AS total_income,
       ROUND(COALESCE(cs.service_cost, 0), 2)                         AS service_cost,
       ROUND(COALESCE(ct.loan_income, 0) + COALESCE(dp.deposit_income, 0)
             + COALESCE(ci.card_fee_income, 0) - COALESCE(cs.service_cost, 0), 2) AS net_contribution,
       RANK() OVER (ORDER BY COALESCE(ct.loan_income, 0) + COALESCE(dp.deposit_income, 0)
             + COALESCE(ci.card_fee_income, 0) - COALESCE(cs.service_cost, 0) DESC) AS contribution_rank
FROM fin_customers c
LEFT JOIN contrib    ct ON c.cust_id = ct.cust_id
LEFT JOIN dep_profit dp ON c.cust_id = dp.cust_id
LEFT JOIN card_inc   ci ON c.cust_id = ci.cust_id
LEFT JOIN cost       cs ON c.cust_id = cs.cust_id
ORDER BY net_contribution DESC
FETCH FIRST 500 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [209] 盈利·产品分析 | 金融 | 产品线盈利分析与资源优化建议
-- ------------------------------------------------------------------------------
WITH prod AS (
    SELECT 'deposit' AS product_line, cust_id, amount AS volume, amount * (0.03 - rate) AS margin_amt
    FROM deposits WHERE status = 'active'
    UNION ALL
    SELECT 'loan', cust_id, loan_amount, loan_amount * interest_rate / 100
    FROM loans WHERE status IN ('active', 'overdue')
    UNION ALL
    SELECT 'fund', h.cust_id, h.cost_amount, h.cost_amount * 0.012
    FROM holdings h
    UNION ALL
    SELECT 'card', c.cust_id, t.amount, t.amount * 0.006
    FROM card_txns t JOIN cards c ON t.card_id = c.card_id
    WHERE t.txn_date >= (TRUNC(SYSDATE) - 365)
)
SELECT product_line,
       COUNT(*)                                     AS txn_cnt,
       COUNT(DISTINCT cust_id)                      AS cust_cnt,
       ROUND(SUM(volume), 2)                        AS total_volume,
       ROUND(SUM(margin_amt), 2)                    AS total_margin,
       ROUND(SUM(margin_amt) / NULLIF(SUM(volume), 0) * 100, 3) AS margin_rate_pct,
       ROUND(SUM(margin_amt) / NULLIF(SUM(SUM(margin_amt)) OVER (), 0) * 100, 2) AS margin_share_pct,
       ROUND(SUM(margin_amt) / NULLIF(COUNT(DISTINCT cust_id), 0), 2) AS margin_per_cust,
       RANK() OVER (ORDER BY SUM(margin_amt) DESC)  AS profitability_rank
FROM prod
GROUP BY product_line
ORDER BY total_margin DESC;

-- ------------------------------------------------------------------------------
-- [210] 看板·全景指标 | 金融 | 金融机构全景经营看板（规模/质量/效益）
-- ------------------------------------------------------------------------------
WITH dep AS (
    SELECT SUM(amount) AS total_deposit FROM deposits WHERE status = 'active'
),
ln AS (
    SELECT SUM(loan_amount) AS total_loan,
           SUM(CASE WHEN status IN ('overdue', 'default') THEN loan_amount ELSE 0 END) AS bad_loan
    FROM loans
),
fin AS (
    SELECT SUM(revenue) AS revenue, SUM(profit) AS profit, SUM(cost) AS cost
    FROM fin_reports
    WHERE period >= TO_CHAR((TRUNC(SYSDATE) - 365), 'YYYY-MM')
),
cust AS (
    SELECT COUNT(*) AS total_cust FROM fin_customers
),
auc AS (
    SELECT SUM(balance) AS total_aum FROM accounts WHERE status = 'active'
)
SELECT ROUND(d.total_deposit, 2)                                    AS total_deposit,
       ROUND(l.total_loan, 2)                                       AS total_loan,
       ROUND(l.bad_loan, 2)                                         AS bad_loan,
       ROUND(l.bad_loan / NULLIF(l.total_loan, 0) * 100, 2)         AS npl_pct,
       ROUND(l.total_loan / NULLIF(d.total_deposit, 0) * 100, 2)    AS ldr_pct,
       ROUND(a.total_aum, 2)                                        AS total_aum,
       ROUND(a.total_aum / NULLIF(c.total_cust, 0), 2)              AS aum_per_cust,
       ROUND(f.revenue, 2)                                          AS revenue,
       ROUND(f.profit, 2)                                           AS profit,
       ROUND(f.profit / NULLIF(f.revenue, 0) * 100, 2)              AS margin_pct,
       ROUND(f.cost / NULLIF(f.revenue, 0) * 100, 2)                AS cost_income_pct,
       c.total_cust                                                 AS total_cust
FROM dep d
CROSS JOIN ln l
CROSS JOIN fin f
CROSS JOIN cust c
CROSS JOIN auc a;

-- ------------------------------------------------------------------------------
-- [211] 递归·组织架构 | 人力 | 组织架构树递归展开与层级路径
-- ------------------------------------------------------------------------------
WITH org_tree(dept_id, dept_name, parent_dept_id, lvl, path) AS (
    SELECT dept_id, dept_name, parent_dept_id, 1 AS lvl,
           CAST(dept_id AS VARCHAR(200)) AS path
    FROM departments
    WHERE parent_dept_id IS NULL
    UNION ALL
    SELECT d.dept_id, d.dept_name, d.parent_dept_id, t.lvl + 1,
           CAST(CONCAT(CONCAT(t.path, '>'), CAST(d.dept_id AS VARCHAR(200))) AS VARCHAR(200))
    FROM departments d
    JOIN org_tree t ON d.parent_dept_id = t.dept_id
)
SELECT t.lvl, t.dept_id, t.dept_name, t.path,
       (SELECT COUNT(*) FROM employees e WHERE e.dept_id = t.dept_id AND e.status = 'active') AS emp_cnt,
       CASE WHEN t.lvl = 1 THEN 'root'
            WHEN t.lvl = 2 THEN 'business_unit'
            WHEN t.lvl = 3 THEN 'department'
            ELSE 'team' END AS org_level
FROM org_tree t
ORDER BY t.path;

-- ------------------------------------------------------------------------------
-- [212] 统计·部门规模 | 人力 | 部门人数、层级与下属部门统计
-- ------------------------------------------------------------------------------
WITH d AS (
    SELECT d1.dept_id, d1.dept_name, d1.parent_dept_id,
           (SELECT COUNT(*) FROM departments d2 WHERE d2.parent_dept_id = d1.dept_id) AS sub_dept_cnt,
           (SELECT COUNT(*) FROM employees e WHERE e.dept_id = d1.dept_id AND e.status = 'active') AS direct_emp
    FROM departments d1
)
SELECT dept_name, parent_dept_id, sub_dept_cnt, direct_emp,
       ROUND(direct_emp / NULLIF(SUM(direct_emp) OVER (), 0) * 100, 2) AS emp_share_pct,
       RANK() OVER (ORDER BY direct_emp DESC) AS size_rank,
       CASE WHEN direct_emp = 0 THEN 'empty_dept'
            WHEN direct_emp < 5 THEN 'small'
            WHEN direct_emp < 20 THEN 'medium'
            ELSE 'large' END AS dept_size
FROM d
ORDER BY direct_emp DESC;

-- ------------------------------------------------------------------------------
-- [213] 递归·汇报链 | 人力 | 员工汇报链路与到 CEO 层级深度
-- ------------------------------------------------------------------------------
WITH chain(emp_id, emp_name, manager_id, depth, chain_path, chain_names) AS (
    SELECT emp_id, emp_name, manager_id, 1 AS depth,
           CAST(emp_id AS VARCHAR(200)) AS chain_path, CAST(emp_name AS VARCHAR(200)) AS chain_names
    FROM employees
    WHERE manager_id IS NULL
    UNION ALL
    SELECT e.emp_id, e.emp_name, e.manager_id, c.depth + 1,
           CAST(CONCAT(CONCAT(c.chain_path, '>'), CAST(e.emp_id AS VARCHAR(200))) AS VARCHAR(200)),
           CAST(CONCAT(CONCAT(c.chain_names, '>'), e.emp_name) AS VARCHAR(200))
    FROM employees e
    JOIN chain c ON e.manager_id = c.emp_id
)
SELECT emp_id, emp_name, depth AS hierarchy_level, chain_path, chain_names,
       CASE WHEN depth <= 2 THEN 'executive_layer'
            WHEN depth <= 4 THEN 'middle_layer'
            ELSE 'frontline' END AS layer_type
FROM chain
ORDER BY depth, chain_path;

-- ------------------------------------------------------------------------------
-- [214] 扁平度·管理幅度 | 人力 | 管理者管理幅度（Span of Control）分析
-- ------------------------------------------------------------------------------
WITH mgr AS (
    SELECT e.manager_id,
           COUNT(*) AS direct_reports
    FROM employees e
    WHERE e.status = 'active' AND e.manager_id IS NOT NULL
    GROUP BY e.manager_id
)
SELECT m.direct_reports,
       COUNT(*)                                          AS manager_cnt,
       ROUND(COUNT(*) / NULLIF(SUM(COUNT(*)) OVER (), 0) * 100, 2) AS mgr_share_pct,
       ROUND(AVG(m.direct_reports) OVER (), 2)           AS avg_span_all
FROM mgr m
GROUP BY m.direct_reports
ORDER BY m.direct_reports;

-- ------------------------------------------------------------------------------
-- [215] 层级·组织深度 | 人力 | 组织层级深度与扁平化程度评估
-- ------------------------------------------------------------------------------
WITH RECURSIVE_TREE AS (
    SELECT 1 AS lvl, dept_id, parent_dept_id FROM departments
),
depth_calc AS (
    SELECT d.dept_id, d.dept_name,
           (SELECT COUNT(*) FROM departments p
            WHERE p.dept_id = d.dept_id) AS self_cnt
    FROM departments d
)
SELECT d.dept_name,
       CASE WHEN d.parent_dept_id IS NULL THEN 1 ELSE 2 END AS approx_level,
       (SELECT COUNT(*) FROM employees e WHERE e.dept_id = d.dept_id AND e.status = 'active') AS emp_cnt,
       (SELECT COUNT(DISTINCT e2.job_level) FROM employees e2
        WHERE e2.dept_id = d.dept_id AND e2.status = 'active') AS job_level_variety,
       ROUND((SELECT COALESCE(AVG(e3.salary), 0) FROM employees e3
              WHERE e3.dept_id = d.dept_id AND e3.status = 'active'), 2) AS avg_salary,
       CASE WHEN (SELECT COUNT(*) FROM departments c WHERE c.parent_dept_id = d.dept_id) = 0
            THEN 'leaf_dept' ELSE 'has_children' END AS node_type
FROM departments d
ORDER BY d.dept_name;

-- ------------------------------------------------------------------------------
-- [216] 分布·司龄分析 | 人力 | 员工司龄分布与留存分析
-- ------------------------------------------------------------------------------
WITH e AS (
    SELECT emp_id, dept_id, job_level, status,
           (COALESCE(leave_date, TRUNC(SYSDATE)) - hire_date) / 365.0 AS tenure_years
    FROM employees
)
SELECT CASE WHEN tenure_years < 1  THEN 'less_than_1y'
            WHEN tenure_years < 3  THEN '1_3y'
            WHEN tenure_years < 5  THEN '3_5y'
            WHEN tenure_years < 10 THEN '5_10y'
            ELSE '10y_plus' END AS tenure_band,
       COUNT(*)                                        AS emp_cnt,
       SUM(CASE WHEN status = 'active' THEN 1 ELSE 0 END) AS active_cnt,
       SUM(CASE WHEN status = 'inactive' THEN 1 ELSE 0 END) AS left_cnt,
       ROUND(SUM(CASE WHEN status = 'active' THEN 1 ELSE 0 END)
             / NULLIF(COUNT(*), 0) * 100, 2)           AS retention_pct,
       ROUND(AVG(tenure_years), 2)                     AS avg_tenure,
       ROUND(COUNT(*) / NULLIF(SUM(COUNT(*)) OVER (), 0) * 100, 2) AS emp_share_pct
FROM e
GROUP BY CASE WHEN tenure_years < 1  THEN 'less_than_1y'
              WHEN tenure_years < 3  THEN '1_3y'
              WHEN tenure_years < 5  THEN '3_5y'
              WHEN tenure_years < 10 THEN '5_10y'
              ELSE '10y_plus' END
ORDER BY avg_tenure;

-- ------------------------------------------------------------------------------
-- [217] 结构·年龄分析 | 人力 | 员工年龄结构与代际分布
-- ------------------------------------------------------------------------------
SELECT CASE WHEN (TRUNC(SYSDATE) - birth_date) / 365 < 26 THEN 'gen_z_below26'
            WHEN (TRUNC(SYSDATE) - birth_date) / 365 < 36 THEN 'millennial_26_35'
            WHEN (TRUNC(SYSDATE) - birth_date) / 365 < 46 THEN 'gen_x_36_45'
            WHEN (TRUNC(SYSDATE) - birth_date) / 365 < 56 THEN 'boomer_46_55'
            ELSE 'near_retirement_56plus' END AS age_band,
       COUNT(*)                                                   AS emp_cnt,
       ROUND(AVG((TRUNC(SYSDATE) - birth_date) / 365.0), 1)   AS avg_age,
       SUM(CASE WHEN gender = 'M' THEN 1 ELSE 0 END)              AS male_cnt,
       SUM(CASE WHEN gender = 'F' THEN 1 ELSE 0 END)              AS female_cnt,
       ROUND(AVG((TRUNC(SYSDATE) - hire_date) / 365.0), 2)    AS avg_tenure,
       ROUND(AVG(salary), 2)                                      AS avg_salary,
       ROUND(COUNT(*) / NULLIF(SUM(COUNT(*)) OVER (), 0) * 100, 2) AS emp_share_pct
FROM employees
WHERE status = 'active'
GROUP BY CASE WHEN (TRUNC(SYSDATE) - birth_date) / 365 < 26 THEN 'gen_z_below26'
              WHEN (TRUNC(SYSDATE) - birth_date) / 365 < 36 THEN 'millennial_26_35'
              WHEN (TRUNC(SYSDATE) - birth_date) / 365 < 46 THEN 'gen_x_36_45'
              WHEN (TRUNC(SYSDATE) - birth_date) / 365 < 56 THEN 'boomer_46_55'
              ELSE 'near_retirement_56plus' END
ORDER BY avg_age;

-- ------------------------------------------------------------------------------
-- [218] 交叉·职级性别 | 人力 | 职级与性别交叉分布分析
-- ------------------------------------------------------------------------------
SELECT job_level,
       COUNT(*)                                                        AS emp_cnt,
       SUM(CASE WHEN gender = 'M' THEN 1 ELSE 0 END)                   AS male_cnt,
       SUM(CASE WHEN gender = 'F' THEN 1 ELSE 0 END)                   AS female_cnt,
       ROUND(SUM(CASE WHEN gender = 'F' THEN 1 ELSE 0 END)
             / NULLIF(COUNT(*), 0) * 100, 2)                           AS female_pct,
       ROUND(AVG(salary), 2)                                           AS avg_salary,
       ROUND(AVG(CASE WHEN gender = 'M' THEN salary END), 2)           AS avg_male_salary,
       ROUND(AVG(CASE WHEN gender = 'F' THEN salary END), 2)           AS avg_female_salary,
       ROUND(AVG(CASE WHEN gender = 'F' THEN salary END)
             / NULLIF(AVG(CASE WHEN gender = 'M' THEN salary END), 0) * 100, 2) AS gender_pay_ratio_pct,
       ROUND(AVG((TRUNC(SYSDATE) - hire_date) / 365.0), 2)         AS avg_tenure
FROM employees
WHERE status = 'active'
GROUP BY job_level
ORDER BY job_level;

-- ------------------------------------------------------------------------------
-- [219] 趋势·人员流动 | 人力 | 月度入职离职趋势与净增长
-- ------------------------------------------------------------------------------
WITH hires AS (
    SELECT TO_CHAR(hire_date, 'YYYY-MM') AS ym, COUNT(*) AS hire_cnt
    FROM employees
    WHERE hire_date >= (TRUNC(SYSDATE) - 730)
    GROUP BY TO_CHAR(hire_date, 'YYYY-MM')
),
leaves AS (
    SELECT TO_CHAR(leave_date, 'YYYY-MM') AS ym, COUNT(*) AS leave_cnt
    FROM employees
    WHERE leave_date IS NOT NULL
      AND leave_date >= (TRUNC(SYSDATE) - 730)
    GROUP BY TO_CHAR(leave_date, 'YYYY-MM')
),
yms AS (
    SELECT ym FROM hires
    UNION
    SELECT ym FROM leaves
)
SELECT y.ym,
       COALESCE(h.hire_cnt, 0)  AS hire_cnt,
       COALESCE(l.leave_cnt, 0) AS leave_cnt,
       COALESCE(h.hire_cnt, 0) - COALESCE(l.leave_cnt, 0) AS net_growth,
       SUM(COALESCE(h.hire_cnt, 0) - COALESCE(l.leave_cnt, 0))
           OVER (ORDER BY y.ym ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS cumulative_headcount,
       ROUND(COALESCE(l.leave_cnt, 0) / NULLIF(COALESCE(h.hire_cnt, 0), 0) * 100, 2) AS leave_hire_ratio_pct
FROM yms y
LEFT JOIN hires  h ON y.ym = h.ym
LEFT JOIN leaves l ON y.ym = l.ym
ORDER BY y.ym;

-- ------------------------------------------------------------------------------
-- [220] 流失·离职分析 | 人力 | 员工流失率与离职高峰分析
-- ------------------------------------------------------------------------------
WITH base AS (
    SELECT TO_CHAR(hire_date, 'YYYY-MM') AS ym,
           COUNT(*) AS cohort_size,
           SUM(CASE WHEN leave_date IS NOT NULL THEN 1 ELSE 0 END) AS left_cnt,
           AVG((leave_date - hire_date)) AS avg_days_to_leave
    FROM employees
    GROUP BY TO_CHAR(hire_date, 'YYYY-MM')
)
SELECT ym, cohort_size, left_cnt,
       ROUND(left_cnt / NULLIF(cohort_size, 0) * 100, 2) AS turnover_pct,
       ROUND(avg_days_to_leave / 30.0, 1)                AS avg_months_to_leave,
       ROUND(AVG(left_cnt / NULLIF(cohort_size, 0) * 100)
             OVER (ORDER BY ym ROWS BETWEEN 5 PRECEDING AND CURRENT ROW), 2) AS turnover_ma6,
       CASE WHEN left_cnt / NULLIF(cohort_size, 0) > 0.3 THEN 'high_turnover'
            WHEN left_cnt / NULLIF(cohort_size, 0) > 0.15 THEN 'moderate'
            ELSE 'low_turnover' END AS turnover_flag
FROM base
ORDER BY ym;

-- ------------------------------------------------------------------------------
-- [221] 留存·新员工 | 人力 | 新员工留存率（入职后 3/6/12 个月）
-- ------------------------------------------------------------------------------
WITH nh AS (
    SELECT emp_id, hire_date, dept_id,
           leave_date,
           (leave_date - hire_date) AS days_to_leave
    FROM employees
    WHERE hire_date >= (TRUNC(SYSDATE) - 730)
)
SELECT TO_CHAR(hire_date, 'YYYY-MM') AS hire_ym,
       COUNT(*)                                                        AS hired_cnt,
       SUM(CASE WHEN leave_date IS NULL OR days_to_leave > 90 THEN 1 ELSE 0 END)  AS retained_3m,
       SUM(CASE WHEN leave_date IS NULL OR days_to_leave > 180 THEN 1 ELSE 0 END) AS retained_6m,
       SUM(CASE WHEN leave_date IS NULL OR days_to_leave > 365 THEN 1 ELSE 0 END) AS retained_12m,
       ROUND(SUM(CASE WHEN leave_date IS NULL OR days_to_leave > 90 THEN 1 ELSE 0 END)
             / NULLIF(COUNT(*), 0) * 100, 2)  AS retention_3m_pct,
       ROUND(SUM(CASE WHEN leave_date IS NULL OR days_to_leave > 180 THEN 1 ELSE 0 END)
             / NULLIF(COUNT(*), 0) * 100, 2)  AS retention_6m_pct,
       ROUND(SUM(CASE WHEN leave_date IS NULL OR days_to_leave > 365 THEN 1 ELSE 0 END)
             / NULLIF(COUNT(*), 0) * 100, 2)  AS retention_12m_pct
FROM nh
GROUP BY TO_CHAR(hire_date, 'YYYY-MM')
ORDER BY hire_ym;

-- ------------------------------------------------------------------------------
-- [222] 风险·离职预警 | 人力 | 部门离职风险与关键岗位流失预警
-- ------------------------------------------------------------------------------
WITH dept_stat AS (
    SELECT dept_id,
           COUNT(*)                                                 AS total_emp,
           SUM(CASE WHEN status = 'inactive' THEN 1 ELSE 0 END)     AS left_cnt,
           SUM(CASE WHEN status = 'active'  THEN 1 ELSE 0 END)      AS active_cnt
    FROM employees GROUP BY dept_id
),
recent_left AS (
    SELECT dept_id, COUNT(*) AS left_180d
    FROM employees
    WHERE leave_date >= (TRUNC(SYSDATE) - 180)
    GROUP BY dept_id
)
SELECT d.dept_name,
       ds.active_cnt,
       ds.left_cnt,
       ROUND(ds.left_cnt / NULLIF(ds.total_emp, 0) * 100, 2)        AS turnover_pct,
       COALESCE(rl.left_180d, 0)                                    AS left_180d,
       ROUND(COALESCE(rl.left_180d, 0) / NULLIF(ds.active_cnt, 0) * 100, 2) AS recent_turnover_pct,
       RANK() OVER (ORDER BY ds.left_cnt / NULLIF(ds.total_emp, 0) DESC) AS risk_rank,
       CASE WHEN COALESCE(rl.left_180d, 0) / NULLIF(ds.active_cnt, 0) > 0.2 THEN 'critical'
            WHEN ds.left_cnt / NULLIF(ds.total_emp, 0) > 0.3 THEN 'high_risk'
            ELSE 'normal' END AS turnover_risk
FROM dept_stat ds
JOIN departments d ON ds.dept_id = d.dept_id
LEFT JOIN recent_left rl ON ds.dept_id = rl.dept_id
ORDER BY recent_turnover_pct DESC;

-- ------------------------------------------------------------------------------
-- [223] 排名·流动率 | 人力 | 部门人员流动率排名与对比
-- ------------------------------------------------------------------------------
SELECT d.dept_name, d.cost_center,
       COUNT(e.emp_id)                                                  AS total_emp,
       SUM(CASE WHEN e.status = 'active' THEN 1 ELSE 0 END)             AS active_emp,
       SUM(CASE WHEN e.leave_date >= (TRUNC(SYSDATE) - 365) THEN 1 ELSE 0 END) AS left_1y,
       ROUND(SUM(CASE WHEN e.leave_date >= (TRUNC(SYSDATE) - 365) THEN 1 ELSE 0 END)
             / NULLIF(SUM(CASE WHEN e.status = 'active' THEN 1 ELSE 0 END), 0) * 100, 2) AS annual_turnover_pct,
       ROUND(AVG((TRUNC(SYSDATE) - e.hire_date) / 365.0), 2)        AS avg_tenure,
       ROUND(AVG(e.salary), 2)                                          AS avg_salary,
       RANK() OVER (ORDER BY SUM(CASE WHEN e.leave_date >= (TRUNC(SYSDATE) - 365) THEN 1 ELSE 0 END)
             / NULLIF(SUM(CASE WHEN e.status = 'active' THEN 1 ELSE 0 END), 0) DESC) AS turnover_rank
FROM departments d
LEFT JOIN employees e ON d.dept_id = e.dept_id
GROUP BY d.dept_name, d.cost_center
HAVING COUNT(e.emp_id) >= 5
ORDER BY annual_turnover_pct DESC;

-- ------------------------------------------------------------------------------
-- [224] 异动·调岗分析 | 人力 | 员工异动（调岗/晋升）记录分析
-- ------------------------------------------------------------------------------
SELECT c.change_type,
       TO_CHAR(c.change_date, 'YYYY-MM') AS change_ym,
       COUNT(*)                                            AS change_cnt,
       COUNT(DISTINCT c.emp_id)                            AS affected_emp,
       ROUND(AVG(c.new_salary - c.old_salary), 2)          AS avg_salary_change,
       ROUND(AVG((c.new_salary - c.old_salary)
             / NULLIF(c.old_salary, 0)) * 100, 2)          AS avg_salary_change_pct,
       SUM(CASE WHEN c.new_dept_id <> c.old_dept_id THEN 1 ELSE 0 END) AS cross_dept_cnt,
       SUM(CASE WHEN c.new_salary > c.old_salary THEN 1 ELSE 0 END)    AS salary_up_cnt,
       ROUND(SUM(CASE WHEN c.new_salary > c.old_salary THEN 1 ELSE 0 END)
             / NULLIF(COUNT(*), 0) * 100, 2)               AS salary_up_pct
FROM emp_changes c
WHERE c.change_date >= (TRUNC(SYSDATE) - 730)
GROUP BY c.change_type, TO_CHAR(c.change_date, 'YYYY-MM')
ORDER BY change_ym DESC, change_cnt DESC;

-- ------------------------------------------------------------------------------
-- [225] 人才·关键识别 | 人力 | 关键人才识别与保留优先级
-- ------------------------------------------------------------------------------
WITH perf AS (
    SELECT emp_id, AVG(score) AS avg_score, COUNT(*) AS review_cnt
    FROM performance
    GROUP BY emp_id
),
tenure AS (
    SELECT emp_id, (TRUNC(SYSDATE) - hire_date) / 365.0 AS tenure_years
    FROM employees
)
SELECT e.emp_id, e.emp_name, e.position, e.job_level, d.dept_name,
       ROUND(p.avg_score, 2)                            AS avg_perf_score,
       ROUND(t.tenure_years, 2)                         AS tenure_years,
       e.salary,
       ROUND(e.salary / NULLIF(AVG(e.salary) OVER (PARTITION BY e.job_level), 0), 2) AS salary_vs_level_avg,
       CASE WHEN p.avg_score >= 90 AND e.job_level >= 5 THEN 'key_talent'
            WHEN p.avg_score >= 90 AND t.tenure_years >= 5 THEN 'core_expert'
            WHEN p.avg_score >= 85 THEN 'high_potential'
            WHEN p.avg_score < 60 THEN 'improvement_needed'
            ELSE 'solid_performer' END AS talent_category,
       CASE WHEN p.avg_score >= 90 AND e.job_level >= 5 THEN 'retention_priority_high'
            WHEN p.avg_score >= 85 THEN 'retention_priority_medium'
            ELSE 'standard' END AS retention_priority
FROM employees e
LEFT JOIN perf p      ON e.emp_id = p.emp_id
LEFT JOIN tenure t    ON e.emp_id = t.emp_id
LEFT JOIN departments d ON e.dept_id = d.dept_id
WHERE e.status = 'active'
ORDER BY p.avg_score DESC, e.job_level DESC
FETCH FIRST 500 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [226] 成本·人力总额 | 人力 | 人力成本总额与月度趋势
-- ------------------------------------------------------------------------------
SELECT p.pay_month,
       COUNT(DISTINCT p.emp_id)                     AS emp_cnt,
       ROUND(SUM(p.gross_pay), 2)                   AS gross_total,
       ROUND(SUM(p.tax), 2)                         AS tax_total,
       ROUND(SUM(p.social_insurance), 2)            AS insurance_total,
       ROUND(SUM(p.net_pay), 2)                     AS net_total,
       ROUND(AVG(p.gross_pay), 2)                   AS avg_gross,
       ROUND(SUM(p.gross_pay) - LAG(SUM(p.gross_pay)) OVER (ORDER BY p.pay_month), 2) AS mom_delta,
       ROUND((SUM(p.gross_pay) - LAG(SUM(p.gross_pay)) OVER (ORDER BY p.pay_month))
             / NULLIF(LAG(SUM(p.gross_pay)) OVER (ORDER BY p.pay_month), 0) * 100, 2) AS mom_pct,
       ROUND(SUM(p.tax + p.social_insurance) / NULLIF(SUM(p.gross_pay), 0) * 100, 2) AS burden_pct
FROM payroll p
WHERE p.pay_month >= TO_CHAR((TRUNC(SYSDATE) - 730), 'YYYY-MM')
GROUP BY p.pay_month
ORDER BY p.pay_month;

-- ------------------------------------------------------------------------------
-- [227] 占比·部门成本 | 人力 | 部门人力成本占比与人均成本
-- ------------------------------------------------------------------------------
SELECT d.dept_name, d.cost_center,
       COUNT(DISTINCT p.emp_id)                                          AS emp_cnt,
       ROUND(SUM(p.gross_pay), 2)                                        AS total_cost,
       ROUND(AVG(p.gross_pay), 2)                                        AS avg_cost_per_emp,
       ROUND(SUM(p.gross_pay) / NULLIF(SUM(SUM(p.gross_pay)) OVER (), 0) * 100, 2) AS cost_share_pct,
       ROUND(SUM(p.tax), 2)                                              AS tax_total,
       ROUND(SUM(p.social_insurance), 2)                                 AS insurance_total,
       RANK() OVER (ORDER BY SUM(p.gross_pay) DESC)                      AS cost_rank,
       ROUND(SUM(p.gross_pay) / NULLIF(COUNT(DISTINCT p.emp_id), 0), 2)  AS cost_per_head
FROM payroll p
JOIN departments d ON p.dept_id = d.dept_id
WHERE p.pay_month >= TO_CHAR((TRUNC(SYSDATE) - 365), 'YYYY-MM')
GROUP BY d.dept_name, d.cost_center
ORDER BY total_cost DESC;

-- ------------------------------------------------------------------------------
-- [228] 带宽·薪酬分析 | 人力 | 职级薪酬带宽与分位数分析
-- ------------------------------------------------------------------------------
WITH s AS (
    SELECT e.job_level, e.salary,
           PERCENT_RANK() OVER (PARTITION BY e.job_level ORDER BY e.salary) AS pct_rank,
           CUME_DIST()    OVER (PARTITION BY e.job_level ORDER BY e.salary) AS cume_dist_val
    FROM employees e
    WHERE e.status = 'active'
)
SELECT job_level,
       COUNT(*)                                                       AS emp_cnt,
       MIN(salary)                                                    AS min_salary,
       MAX(salary)                                                    AS max_salary,
       ROUND(AVG(salary), 2)                                          AS avg_salary,
       MAX(CASE WHEN pct_rank <= 0.25 THEN salary END)                AS p25,
       MAX(CASE WHEN pct_rank <= 0.50 THEN salary END)                AS p50,
       MAX(CASE WHEN pct_rank <= 0.75 THEN salary END)                AS p75,
       ROUND(MAX(CASE WHEN pct_rank <= 0.75 THEN salary END)
             / NULLIF(MAX(CASE WHEN pct_rank <= 0.25 THEN salary END), 0), 2) AS bandwidth_ratio,
       ROUND((MAX(salary) - MIN(salary)) / NULLIF(AVG(salary), 0) * 100, 2) AS range_spread_pct
FROM s
GROUP BY job_level
ORDER BY job_level;

-- ------------------------------------------------------------------------------
-- [229] 公平·同工同酬 | 人力 | 同职级薪酬差异与同工同酬分析
-- ------------------------------------------------------------------------------
WITH lv AS (
    SELECT e.emp_id, e.job_level, e.position, e.gender, e.salary, e.dept_id,
           (TRUNC(SYSDATE) - e.hire_date) / 365.0 AS tenure_years,
           AVG(e.salary) OVER (PARTITION BY e.job_level) AS level_avg_salary
    FROM employees e
    WHERE e.status = 'active'
)
SELECT job_level,
       COUNT(*)                                                          AS emp_cnt,
       ROUND(MAX(level_avg_salary), 2)                                   AS level_avg_salary,
       ROUND(MAX(salary) - MIN(salary), 2)                               AS salary_range,
       ROUND((MAX(salary) - MIN(salary)) / NULLIF(MAX(level_avg_salary), 0) * 100, 2) AS range_vs_avg_pct,
       ROUND(AVG(ABS(salary - level_avg_salary) / NULLIF(level_avg_salary, 0)) * 100, 2) AS avg_deviation_pct,
       SUM(CASE WHEN salary > level_avg_salary * 1.3 THEN 1 ELSE 0 END)  AS above_band_cnt,
       SUM(CASE WHEN salary < level_avg_salary * 0.7 THEN 1 ELSE 0 END)  AS below_band_cnt,
       CASE WHEN SUM(CASE WHEN salary < level_avg_salary * 0.7 THEN 1 ELSE 0 END) > COUNT(*) * 0.2
            THEN 'compression_issue' ELSE 'normal' END AS pay_equity_flag
FROM lv
GROUP BY job_level
ORDER BY job_level;

-- ------------------------------------------------------------------------------
-- [230] 竞争·薪酬定位 | 人力 | 薪酬竞争力分析（内部分位 vs 市场中位）
-- ------------------------------------------------------------------------------
WITH emp AS (
    SELECT e.emp_id, e.job_level, e.position, e.salary, e.dept_id,
           PERCENT_RANK() OVER (PARTITION BY e.job_level ORDER BY e.salary) AS internal_pctile,
           AVG(e.salary) OVER (PARTITION BY e.job_level)                    AS level_avg
    FROM employees e
    WHERE e.status = 'active'
),
market AS (
    SELECT job_level, AVG(salary) AS market_median
    FROM employees
    WHERE status = 'active'
    GROUP BY job_level
)
SELECT e.job_level,
       COUNT(*)                                                        AS emp_cnt,
       ROUND(AVG(e.salary), 2)                                         AS avg_salary,
       ROUND(m.market_median, 2)                                       AS market_median,
       ROUND(AVG(e.salary) / NULLIF(m.market_median, 0), 3)            AS compa_ratio,
       SUM(CASE WHEN e.salary < m.market_median * 0.8 THEN 1 ELSE 0 END) AS below_market_cnt,
       SUM(CASE WHEN e.salary > m.market_median * 1.2 THEN 1 ELSE 0 END) AS above_market_cnt,
       ROUND(SUM(CASE WHEN e.salary < m.market_median * 0.8 THEN 1 ELSE 0 END)
             / NULLIF(COUNT(*), 0) * 100, 2)                           AS below_market_pct,
       CASE WHEN AVG(e.salary) / NULLIF(m.market_median, 0) < 0.9 THEN 'below_market'
            WHEN AVG(e.salary) / NULLIF(m.market_median, 0) > 1.1 THEN 'above_market'
            ELSE 'at_market' END AS market_position
FROM emp e
JOIN market m ON e.job_level = m.job_level
GROUP BY e.job_level, m.market_median
ORDER BY e.job_level;

-- ------------------------------------------------------------------------------
-- [231] 调薪·幅度分析 | 人力 | 调薪幅度分布与调薪覆盖率
-- ------------------------------------------------------------------------------
WITH sal AS (
    SELECT s.emp_id, s.effective_date, s.total_salary,
           LAG(s.total_salary) OVER (PARTITION BY s.emp_id ORDER BY s.effective_date) AS prev_salary,
           EXTRACT(YEAR FROM s.effective_date) AS adj_year
    FROM salaries s
)
SELECT adj_year,
       COUNT(*)                                                        AS adjustment_cnt,
       COUNT(DISTINCT emp_id)                                          AS adjusted_emp,
       ROUND(AVG(total_salary - prev_salary), 2)                       AS avg_increase_amt,
       ROUND(AVG((total_salary - prev_salary) / NULLIF(prev_salary, 0)) * 100, 2) AS avg_increase_pct,
       MAX((total_salary - prev_salary) / NULLIF(prev_salary, 0)) * 100 AS max_increase_pct,
       MIN((total_salary - prev_salary) / NULLIF(prev_salary, 0)) * 100 AS min_increase_pct,
       SUM(CASE WHEN (total_salary - prev_salary) / NULLIF(prev_salary, 0) < 0 THEN 1 ELSE 0 END) AS decrease_cnt,
       SUM(CASE WHEN (total_salary - prev_salary) / NULLIF(prev_salary, 0) > 0.1 THEN 1 ELSE 0 END) AS large_increase_cnt
FROM sal
WHERE prev_salary IS NOT NULL
GROUP BY adj_year
ORDER BY adj_year DESC;

-- ------------------------------------------------------------------------------
-- [232] 关联·调薪绩效 | 人力 | 调薪幅度与绩效得分关联分析
-- ------------------------------------------------------------------------------
WITH perf AS (
    SELECT emp_id, AVG(score) AS avg_score
    FROM performance
    GROUP BY emp_id
),
sal_chg AS (
    SELECT s.emp_id,
           MAX(s.total_salary) - MIN(s.total_salary)                     AS total_increase,
           MIN(s.total_salary)                                           AS base_salary,
           COUNT(*)                                                      AS adj_times
    FROM salaries s
    GROUP BY s.emp_id
    HAVING COUNT(*) > 1
)
SELECT CASE WHEN p.avg_score >= 90 THEN 'excellent_90plus'
            WHEN p.avg_score >= 80 THEN 'good_80_90'
            WHEN p.avg_score >= 70 THEN 'average_70_80'
            ELSE 'below_70' END AS perf_band,
       COUNT(*)                                                          AS emp_cnt,
       ROUND(AVG(sc.total_increase), 2)                                  AS avg_increase_amt,
       ROUND(AVG(sc.total_increase / NULLIF(sc.base_salary, 0)) * 100, 2) AS avg_increase_pct,
       ROUND(AVG(sc.adj_times), 2)                                       AS avg_adjust_times,
       ROUND(AVG(sc.base_salary), 2)                                     AS avg_base_salary,
       ((COUNT(*) * SUM((p.avg_score) * (sc.total_increase / NULLIF(sc.base_salary, 0))) - SUM(p.avg_score) * SUM(sc.total_increase / NULLIF(sc.base_salary, 0))) / NULLIF(SQRT((COUNT(*) * SUM((p.avg_score) * (p.avg_score)) - SUM(p.avg_score) * SUM(p.avg_score)) * (COUNT(*) * SUM((sc.total_increase / NULLIF(sc.base_salary, 0)) * (sc.total_increase / NULLIF(sc.base_salary, 0))) - SUM(sc.total_increase / NULLIF(sc.base_salary, 0)) * SUM(sc.total_increase / NULLIF(sc.base_salary, 0)))), 0))  AS corr_score_increase
FROM sal_chg sc
JOIN perf p ON sc.emp_id = p.emp_id
GROUP BY CASE WHEN p.avg_score >= 90 THEN 'excellent_90plus'
              WHEN p.avg_score >= 80 THEN 'good_80_90'
              WHEN p.avg_score >= 70 THEN 'average_70_80'
              ELSE 'below_70' END
ORDER BY avg_increase_pct DESC;

-- ------------------------------------------------------------------------------
-- [233] 预算·薪酬执行 | 人力 | 薪酬预算执行率与偏差分析
-- ------------------------------------------------------------------------------
WITH m AS (
    SELECT p.pay_month, p.dept_id,
           SUM(p.gross_pay) AS actual_cost,
           COUNT(DISTINCT p.emp_id) AS headcount
    FROM payroll p
    GROUP BY p.pay_month, p.dept_id
),
bm AS (
    SELECT pay_month, dept_id,
           AVG(actual_cost) OVER (PARTITION BY dept_id) AS budget_cost
    FROM m
)
SELECT d.dept_name, m.pay_month,
       ROUND(m.actual_cost, 2)                                          AS actual_cost,
       ROUND(MAX(bm.budget_cost), 2)                                    AS budget_cost,
       ROUND(m.actual_cost / NULLIF(MAX(bm.budget_cost), 0) * 100, 2)   AS execution_pct,
       ROUND(m.actual_cost - MAX(bm.budget_cost), 2)                    AS variance,
       m.headcount,
       ROUND(m.actual_cost / NULLIF(m.headcount, 0), 2)                 AS cost_per_head,
       CASE WHEN m.actual_cost / NULLIF(MAX(bm.budget_cost), 0) > 1.15 THEN 'over_budget'
            WHEN m.actual_cost / NULLIF(MAX(bm.budget_cost), 0) < 0.85 THEN 'under_budget'
            ELSE 'on_budget' END AS budget_status
FROM m
JOIN bm ON m.pay_month = bm.pay_month AND m.dept_id = bm.dept_id
JOIN departments d ON m.dept_id = d.dept_id
GROUP BY d.dept_name, m.pay_month, m.actual_cost, m.headcount
ORDER BY m.pay_month DESC, execution_pct DESC;

-- ------------------------------------------------------------------------------
-- [234] 排名·薪酬分位 | 人力 | 员工薪酬排名与部门内分位
-- ------------------------------------------------------------------------------
WITH s AS (
    SELECT e.emp_id, e.emp_name, e.dept_id, e.job_level, e.salary,
           RANK() OVER (ORDER BY e.salary DESC)                        AS rank_all,
           RANK() OVER (PARTITION BY e.dept_id ORDER BY e.salary DESC) AS rank_in_dept,
           PERCENT_RANK() OVER (PARTITION BY e.dept_id ORDER BY e.salary) AS pct_in_dept,
           AVG(e.salary) OVER (PARTITION BY e.dept_id)                 AS dept_avg_salary
    FROM employees e
    WHERE e.status = 'active'
)
SELECT emp_id, emp_name, job_level,
       ROUND(salary, 2)                                  AS salary,
       rank_all, rank_in_dept,
       ROUND(CAST(pct_in_dept * 100 AS NUMBER), 1)              AS percentile_in_dept,
       ROUND(dept_avg_salary, 2)                         AS dept_avg_salary,
       ROUND(salary / NULLIF(dept_avg_salary, 0), 2)     AS vs_dept_avg,
       CASE WHEN pct_in_dept >= 0.9 THEN 'top10pct'
            WHEN pct_in_dept >= 0.75 THEN 'top25pct'
            WHEN pct_in_dept <= 0.1 THEN 'bottom10pct'
            ELSE 'middle' END AS salary_position
FROM s
ORDER BY salary DESC
FETCH FIRST 500 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [235] 加班·费用分析 | 人力 | 加班时长与加班费分析
-- ------------------------------------------------------------------------------
WITH ot AS (
    SELECT a.emp_id, e.dept_id, e.job_level,
           EXTRACT(MONTH FROM a.att_date) AS mon,
           SUM(a.overtime_hours)                                   AS total_ot,
           COUNT(*)                                                AS work_days,
           SUM(CASE WHEN a.overtime_hours > 0 THEN 1 ELSE 0 END)   AS ot_days
    FROM attendance a
    JOIN employees e ON a.emp_id = e.emp_id
    WHERE a.att_date >= (TRUNC(SYSDATE) - 365)
    GROUP BY a.emp_id, e.dept_id, e.job_level, EXTRACT(MONTH FROM a.att_date)
)
SELECT job_level,
       COUNT(DISTINCT emp_id)                                        AS emp_cnt,
       ROUND(SUM(total_ot), 1)                                       AS total_ot_hours,
       ROUND(AVG(total_ot), 1)                                       AS avg_ot_per_emp,
       ROUND(MAX(total_ot), 1)                                       AS max_ot,
       ROUND(AVG(total_ot) / NULLIF(AVG(work_days), 0), 2)           AS avg_ot_per_day,
       ROUND(SUM(total_ot) / NULLIF(SUM(SUM(total_ot)) OVER (), 0) * 100, 2) AS ot_share_pct,
       SUM(CASE WHEN total_ot > 80 THEN 1 ELSE 0 END)                AS heavy_ot_emp,
       ROUND(SUM(CASE WHEN total_ot > 80 THEN 1 ELSE 0 END)
             / NULLIF(COUNT(*), 0) * 100, 2)                         AS heavy_ot_pct
FROM ot
GROUP BY job_level
ORDER BY avg_ot_per_emp DESC;

-- ------------------------------------------------------------------------------
-- [236] 税费·社保分析 | 人力 | 个税与社保负担分析
-- ------------------------------------------------------------------------------
SELECT CASE WHEN p.gross_pay < 5000   THEN 'below_5k'
            WHEN p.gross_pay < 10000  THEN '5k_10k'
            WHEN p.gross_pay < 20000  THEN '10k_20k'
            WHEN p.gross_pay < 40000  THEN '20k_40k'
            ELSE 'above_40k' END AS income_band,
       COUNT(*)                                                         AS record_cnt,
       ROUND(AVG(p.gross_pay), 2)                                       AS avg_gross,
       ROUND(AVG(p.tax), 2)                                             AS avg_tax,
       ROUND(AVG(p.social_insurance), 2)                                AS avg_insurance,
       ROUND(AVG(p.net_pay), 2)                                         AS avg_net,
       ROUND(AVG(p.tax) / NULLIF(AVG(p.gross_pay), 0) * 100, 2)         AS tax_rate_pct,
       ROUND(AVG(p.social_insurance) / NULLIF(AVG(p.gross_pay), 0) * 100, 2) AS insurance_rate_pct,
       ROUND(AVG(p.net_pay) / NULLIF(AVG(p.gross_pay), 0) * 100, 2)     AS net_rate_pct
FROM payroll p
WHERE p.pay_month >= TO_CHAR((TRUNC(SYSDATE) - 365), 'YYYY-MM')
GROUP BY CASE WHEN p.gross_pay < 5000   THEN 'below_5k'
              WHEN p.gross_pay < 10000  THEN '5k_10k'
              WHEN p.gross_pay < 20000  THEN '10k_20k'
              WHEN p.gross_pay < 40000  THEN '20k_40k'
              ELSE 'above_40k' END
ORDER BY avg_gross;

-- ------------------------------------------------------------------------------
-- [237] 分布·实发工资 | 人力 | 实发工资分布与收入离散度
-- ------------------------------------------------------------------------------
WITH p AS (
    SELECT emp_id, net_pay,
           NTILE(10) OVER (ORDER BY net_pay) AS decile
    FROM payroll
    WHERE pay_month = (SELECT MAX(pay_month) FROM payroll)
)
SELECT decile,
       COUNT(*)                                     AS emp_cnt,
       MIN(net_pay)                                 AS min_net,
       MAX(net_pay)                                 AS max_net,
       ROUND(AVG(net_pay), 2)                       AS avg_net,
       ROUND(SUM(net_pay) / NULLIF(SUM(SUM(net_pay)) OVER (), 0) * 100, 2) AS income_share_pct,
       ROUND(SUM(SUM(net_pay)) OVER (ORDER BY decile
             ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)
             / NULLIF(SUM(SUM(net_pay)) OVER (), 0) * 100, 2) AS cumulative_share_pct
FROM p
GROUP BY decile
ORDER BY decile;

-- ------------------------------------------------------------------------------
-- [238] 人均·部门薪酬 | 人力 | 部门人均薪酬与薪酬效率
-- ------------------------------------------------------------------------------
SELECT d.dept_name,
       COUNT(DISTINCT e.emp_id)                                          AS headcount,
       ROUND(SUM(e.salary), 2)                                           AS total_salary,
       ROUND(AVG(e.salary), 2)                                           AS avg_salary,
       ROUND(MIN(e.salary), 2)                                           AS min_salary,
       ROUND(MAX(e.salary), 2)                                           AS max_salary,
       ROUND(MAX(e.salary) / NULLIF(MIN(e.salary), 0), 2)                AS internal_gap,
       ROUND(AVG(e.job_level), 2)                                        AS avg_job_level,
       ROUND(AVG((TRUNC(SYSDATE) - e.hire_date) / 365.0), 2)         AS avg_tenure,
       RANK() OVER (ORDER BY AVG(e.salary) DESC)                         AS salary_rank,
       ROUND(AVG(e.salary) / NULLIF(AVG(AVG(e.salary)) OVER (), 0), 2)   AS vs_company_avg
FROM departments d
JOIN employees e ON d.dept_id = e.dept_id
WHERE e.status = 'active'
GROUP BY d.dept_name
ORDER BY avg_salary DESC;

-- ------------------------------------------------------------------------------
-- [239] 结构·固浮比 | 人力 | 薪酬结构分析（基本工资/奖金/津贴占比）
-- ------------------------------------------------------------------------------
WITH s AS (
    SELECT s.emp_id, e.job_level, e.dept_id,
           s.base_salary, s.bonus, s.allowance, s.total_salary
    FROM salaries s
    JOIN employees e ON s.emp_id = e.emp_id
    WHERE s.effective_date = (SELECT MAX(effective_date) FROM salaries s2 WHERE s2.emp_id = s.emp_id)
)
SELECT job_level,
       COUNT(*)                                                            AS emp_cnt,
       ROUND(AVG(base_salary), 2)                                          AS avg_base,
       ROUND(AVG(bonus), 2)                                                AS avg_bonus,
       ROUND(AVG(allowance), 2)                                            AS avg_allowance,
       ROUND(AVG(total_salary), 2)                                         AS avg_total,
       ROUND(AVG(base_salary) / NULLIF(AVG(total_salary), 0) * 100, 2)     AS fixed_pct,
       ROUND(AVG(bonus) / NULLIF(AVG(total_salary), 0) * 100, 2)           AS variable_pct,
       ROUND(AVG(allowance) / NULLIF(AVG(total_salary), 0) * 100, 2)       AS allowance_pct,
       CASE WHEN AVG(bonus) / NULLIF(AVG(total_salary), 0) > 0.3 THEN 'high_variable'
            WHEN AVG(bonus) / NULLIF(AVG(total_salary), 0) < 0.1 THEN 'low_variable'
            ELSE 'balanced' END AS pay_mix_type
FROM s
GROUP BY job_level
ORDER BY job_level;

-- ------------------------------------------------------------------------------
-- [240] 出勤·考勤分析 | 人力 | 员工出勤率与缺勤分析
-- ------------------------------------------------------------------------------
WITH a AS (
    SELECT emp_id,
           COUNT(*)                                                   AS total_days,
           SUM(CASE WHEN status = 'normal'     THEN 1 ELSE 0 END)     AS normal_days,
           SUM(CASE WHEN status = 'late'       THEN 1 ELSE 0 END)     AS late_days,
           SUM(CASE WHEN status = 'early_leave' THEN 1 ELSE 0 END)    AS early_leave_days,
           SUM(CASE WHEN status = 'absent'     THEN 1 ELSE 0 END)     AS absent_days,
           SUM(CASE WHEN status = 'leave'      THEN 1 ELSE 0 END)     AS leave_days,
           ROUND(AVG(work_hours), 2)                                  AS avg_work_hours
    FROM attendance
    WHERE att_date >= (TRUNC(SYSDATE) - 90)
    GROUP BY emp_id
)
SELECT a.emp_id, e.emp_name, d.dept_name,
       a.total_days, a.normal_days, a.late_days, a.absent_days, a.leave_days,
       ROUND(a.normal_days / NULLIF(a.total_days, 0) * 100, 2)              AS attendance_rate_pct,
       ROUND((a.late_days + a.early_leave_days) / NULLIF(a.total_days, 0) * 100, 2) AS irregular_pct,
       a.avg_work_hours,
       CASE WHEN a.normal_days / NULLIF(a.total_days, 0) < 0.8 THEN 'poor_attendance'
            WHEN a.late_days > 5 THEN 'frequent_late'
            ELSE 'good' END AS attendance_grade
FROM a
JOIN employees   e ON a.emp_id = e.emp_id
JOIN departments d ON e.dept_id = d.dept_id
ORDER BY attendance_rate_pct ASC
FETCH FIRST 500 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [241] 迟到·异常识别 | 人力 | 迟到早退分析与高频异常员工
-- ------------------------------------------------------------------------------
WITH late AS (
    SELECT a.emp_id, e.dept_id, e.emp_name,
           COUNT(*)                                                    AS total_days,
           SUM(CASE WHEN a.status = 'late' THEN 1 ELSE 0 END)          AS late_cnt,
           SUM(CASE WHEN a.status = 'early_leave' THEN 1 ELSE 0 END)   AS early_cnt,
           MIN(CASE WHEN a.status = 'late' THEN a.check_in END)        AS earliest_checkin,
           MAX(CASE WHEN a.status = 'late' THEN a.check_in END)        AS latest_checkin
    FROM attendance a
    JOIN employees e ON a.emp_id = e.emp_id
    WHERE a.att_date >= (TRUNC(SYSDATE) - 180)
    GROUP BY a.emp_id, e.dept_id, e.emp_name
)
SELECT d.dept_name,
       COUNT(*)                                                        AS emp_cnt,
       SUM(late_cnt)                                                   AS total_late,
       ROUND(AVG(late_cnt), 2)                                         AS avg_late_per_emp,
       SUM(CASE WHEN late_cnt >= 10 THEN 1 ELSE 0 END)                 AS chronic_late_emp,
       ROUND(SUM(late_cnt) / NULLIF(SUM(total_days), 0) * 100, 2)      AS late_rate_pct,
       RANK() OVER (ORDER BY SUM(late_cnt) / NULLIF(SUM(total_days), 0) DESC) AS late_rank
FROM late
JOIN departments d ON late.dept_id = d.dept_id
GROUP BY d.dept_name
ORDER BY late_rate_pct DESC;

-- ------------------------------------------------------------------------------
-- [242] 加班·部门对比 | 人力 | 部门加班强度与健康度预警
-- ------------------------------------------------------------------------------
WITH ot AS (
    SELECT e.dept_id, a.emp_id,
           SUM(a.overtime_hours) AS ot_hours,
           COUNT(*)              AS work_days,
           SUM(a.work_hours)     AS total_hours
    FROM attendance a
    JOIN employees e ON a.emp_id = e.emp_id
    WHERE a.att_date >= (TRUNC(SYSDATE) - 180)
    GROUP BY e.dept_id, a.emp_id
)
SELECT d.dept_name,
       COUNT(*)                                                      AS emp_cnt,
       ROUND(SUM(ot.ot_hours), 1)                                    AS total_ot_hours,
       ROUND(AVG(ot.ot_hours), 1)                                    AS avg_ot_per_emp,
       ROUND(AVG(ot.ot_hours) / NULLIF(AVG(ot.work_days), 0), 2)     AS avg_ot_per_day,
       ROUND(SUM(ot.ot_hours) / NULLIF(SUM(ot.total_hours), 0) * 100, 2) AS ot_ratio_pct,
       SUM(CASE WHEN ot.ot_hours > 100 THEN 1 ELSE 0 END)            AS overworked_emp,
       CASE WHEN AVG(ot.ot_hours) / NULLIF(AVG(ot.work_days), 0) > 3 THEN 'burnout_risk'
            WHEN AVG(ot.ot_hours) / NULLIF(AVG(ot.work_days), 0) > 1.5 THEN 'high_load'
            ELSE 'healthy' END AS workload_flag
FROM ot
JOIN departments d ON ot.dept_id = d.dept_id
GROUP BY d.dept_name
ORDER BY avg_ot_per_day DESC;

-- ------------------------------------------------------------------------------
-- [243] 连续·异常考勤 | 人力 | 连续异常考勤识别（连续迟到/缺勤）
-- ------------------------------------------------------------------------------
WITH a AS (
    SELECT emp_id, att_date, status,
           CASE WHEN status IN ('late', 'absent') THEN 1 ELSE 0 END AS is_abnormal
    FROM attendance
    WHERE att_date >= (TRUNC(SYSDATE) - 90)
),
grp AS (
    SELECT emp_id, att_date, status, is_abnormal,
           (att_date - DATE '2000-01-01')
             - ROW_NUMBER() OVER (PARTITION BY emp_id ORDER BY att_date) AS island_id
    FROM a
),
runs AS (
    SELECT emp_id, island_id,
           SUM(is_abnormal) AS abnormal_days,
           COUNT(*)         AS span_days,
           MIN(att_date)    AS start_dt,
           MAX(att_date)    AS end_dt
    FROM grp
    GROUP BY emp_id, island_id
)
SELECT r.emp_id, e.emp_name, d.dept_name,
       r.abnormal_days, r.span_days, r.start_dt, r.end_dt,
       ROUND(r.abnormal_days / NULLIF(r.span_days, 0) * 100, 2) AS abnormal_density_pct,
       CASE WHEN r.abnormal_days >= 5 THEN 'severe_pattern'
            WHEN r.abnormal_days >= 3 THEN 'concerning'
            ELSE 'minor' END AS pattern_severity
FROM runs r
JOIN employees   e ON r.emp_id = e.emp_id
JOIN departments d ON e.dept_id = d.dept_id
WHERE r.abnormal_days >= 3
ORDER BY r.abnormal_days DESC
FETCH FIRST 300 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [244] 假期·使用分析 | 人力 | 假期使用情况与类型分布
-- ------------------------------------------------------------------------------
SELECT l.leave_type,
       COUNT(*)                                                AS request_cnt,
       COUNT(DISTINCT l.emp_id)                                AS emp_cnt,
       SUM(l.days)                                             AS total_days,
       ROUND(AVG(l.days), 2)                                   AS avg_days,
       SUM(CASE WHEN l.status = 'approved' THEN l.days ELSE 0 END) AS approved_days,
       SUM(CASE WHEN l.status = 'rejected' THEN 1 ELSE 0 END)  AS rejected_cnt,
       ROUND(SUM(CASE WHEN l.status = 'rejected' THEN 1 ELSE 0 END)
             / NULLIF(COUNT(*), 0) * 100, 2)                   AS reject_rate_pct,
       ROUND(SUM(l.days) / NULLIF(SUM(SUM(l.days)) OVER (), 0) * 100, 2) AS days_share_pct
FROM leave_requests l
WHERE l.start_date >= (TRUNC(SYSDATE) - 365)
GROUP BY l.leave_type
ORDER BY total_days DESC;

-- ------------------------------------------------------------------------------
-- [245] 假期·余额预警 | 人力 | 假期额度使用与过期预警
-- ------------------------------------------------------------------------------
WITH used AS (
    SELECT emp_id, leave_type, SUM(days) AS used_days
    FROM leave_requests
    WHERE status = 'approved'
      AND EXTRACT(YEAR FROM start_date) = EXTRACT(YEAR FROM TRUNC(SYSDATE))
    GROUP BY emp_id, leave_type
)
SELECT e.emp_id, e.emp_name, d.dept_name,
       u.leave_type,
       u.used_days,
       10 AS annual_entitlement,
       ROUND(u.used_days / 10.0 * 100, 2)                     AS usage_pct,
       10 - u.used_days                                        AS remaining_days,
       (DATE '2026-12-31' - TRUNC(SYSDATE))                 AS days_to_expire,
       CASE WHEN u.used_days / 10.0 >= 0.9 THEN 'nearly_exhausted'
            WHEN u.used_days / 10.0 >= 0.5 THEN 'moderate_use'
            ELSE 'low_use_expiry_risk' END AS leave_status
FROM used u
JOIN employees   e ON u.emp_id = e.emp_id
JOIN departments d ON e.dept_id = d.dept_id
WHERE e.status = 'active'
ORDER BY usage_pct DESC
FETCH FIRST 500 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [246] 分布·请假类型 | 人力 | 请假类型与部门/月份交叉分布
-- ------------------------------------------------------------------------------
SELECT d.dept_name, l.leave_type, EXTRACT(MONTH FROM l.start_date) AS mon,
       COUNT(*)                    AS request_cnt,
       SUM(l.days)                 AS total_days,
       ROUND(AVG(l.days), 2)       AS avg_days,
       COUNT(DISTINCT l.emp_id)    AS emp_cnt,
       ROUND(SUM(l.days) / NULLIF(SUM(SUM(l.days)) OVER (PARTITION BY d.dept_name), 0) * 100, 2) AS dept_share_pct
FROM leave_requests l
JOIN employees   e ON l.emp_id = e.emp_id
JOIN departments d ON e.dept_id = d.dept_id
WHERE l.start_date >= (TRUNC(SYSDATE) - 365)
GROUP BY d.dept_name, l.leave_type, EXTRACT(MONTH FROM l.start_date)
ORDER BY d.dept_name, mon;

-- ------------------------------------------------------------------------------
-- [247] 时长·工作分布 | 人力 | 员工工作时长分布与效率分析
-- ------------------------------------------------------------------------------
SELECT CASE WHEN a.work_hours < 6  THEN 'short_below6'
            WHEN a.work_hours < 8  THEN 'standard_6_8'
            WHEN a.work_hours < 10 THEN 'normal_8_10'
            WHEN a.work_hours < 12 THEN 'long_10_12'
            ELSE 'excessive_12plus' END AS work_hours_band,
       COUNT(*)                                                    AS record_cnt,
       COUNT(DISTINCT a.emp_id)                                    AS emp_cnt,
       ROUND(AVG(a.work_hours), 2)                                 AS avg_hours,
       ROUND(AVG(a.overtime_hours), 2)                             AS avg_overtime,
       ROUND(COUNT(*) / NULLIF(SUM(COUNT(*)) OVER (), 0) * 100, 2) AS record_share_pct,
       ROUND(AVG(a.work_hours - a.overtime_hours), 2)              AS avg_regular_hours
FROM attendance a
WHERE a.att_date >= (TRUNC(SYSDATE) - 180)
GROUP BY CASE WHEN a.work_hours < 6  THEN 'short_below6'
              WHEN a.work_hours < 8  THEN 'standard_6_8'
              WHEN a.work_hours < 10 THEN 'normal_8_10'
              WHEN a.work_hours < 12 THEN 'long_10_12'
              ELSE 'excessive_12plus' END
ORDER BY avg_hours;

-- ------------------------------------------------------------------------------
-- [248] 弹性·远程办公 | 人力 | 弹性办公与在岗模式分析
-- ------------------------------------------------------------------------------
WITH att_mode AS (
    SELECT a.emp_id, e.dept_id,
           COUNT(*)                                                        AS total_days,
           SUM(CASE WHEN a.status = 'remote'    THEN 1 ELSE 0 END)         AS remote_days,
           SUM(CASE WHEN a.status = 'normal'    THEN 1 ELSE 0 END)         AS onsite_days,
           SUM(CASE WHEN a.status = 'business_trip' THEN 1 ELSE 0 END)     AS trip_days,
           ROUND(AVG(CASE WHEN a.status = 'remote' THEN a.work_hours END), 2) AS avg_remote_hours,
           ROUND(AVG(CASE WHEN a.status = 'normal' THEN a.work_hours END), 2) AS avg_onsite_hours
    FROM attendance a
    JOIN employees e ON a.emp_id = e.emp_id
    WHERE a.att_date >= (TRUNC(SYSDATE) - 180)
    GROUP BY a.emp_id, e.dept_id
)
SELECT d.dept_name,
       COUNT(*)                                                       AS emp_cnt,
       ROUND(AVG(m.remote_days), 1)                                   AS avg_remote_days,
       ROUND(AVG(m.onsite_days), 1)                                   AS avg_onsite_days,
       ROUND(AVG(m.remote_days) / NULLIF(AVG(m.total_days), 0) * 100, 2) AS remote_pct,
       ROUND(AVG(m.avg_remote_hours), 2)                              AS avg_remote_hours,
       ROUND(AVG(m.avg_onsite_hours), 2)                              AS avg_onsite_hours,
       ROUND(AVG(m.avg_remote_hours) - AVG(m.avg_onsite_hours), 2)    AS productivity_gap_hours,
       CASE WHEN AVG(m.remote_days) / NULLIF(AVG(m.total_days), 0) > 0.6 THEN 'remote_first'
            WHEN AVG(m.remote_days) / NULLIF(AVG(m.total_days), 0) > 0.2 THEN 'hybrid'
            ELSE 'onsite_first' END AS work_mode
FROM att_mode m
JOIN departments d ON m.dept_id = d.dept_id
GROUP BY d.dept_name
ORDER BY remote_pct DESC;

-- ------------------------------------------------------------------------------
-- [249] 关联·考勤绩效 | 人力 | 考勤表现与绩效得分关联分析
-- ------------------------------------------------------------------------------
WITH att AS (
    SELECT a.emp_id,
           COUNT(*)                                                   AS total_days,
           SUM(CASE WHEN a.status = 'normal' THEN 1 ELSE 0 END)       AS normal_days,
           SUM(CASE WHEN a.status IN ('late', 'absent') THEN 1 ELSE 0 END) AS abnormal_days,
           SUM(a.overtime_hours)                                      AS total_ot
    FROM attendance a
    WHERE a.att_date >= (TRUNC(SYSDATE) - 365)
    GROUP BY a.emp_id
),
perf AS (
    SELECT emp_id, AVG(score) AS avg_score
    FROM performance GROUP BY emp_id
)
SELECT CASE WHEN a.normal_days / NULLIF(a.total_days, 0) >= 0.95 THEN 'excellent_attendance'
            WHEN a.normal_days / NULLIF(a.total_days, 0) >= 0.85 THEN 'good_attendance'
            WHEN a.normal_days / NULLIF(a.total_days, 0) >= 0.70 THEN 'fair_attendance'
            ELSE 'poor_attendance' END AS attendance_level,
       COUNT(*)                                                        AS emp_cnt,
       ROUND(AVG(p.avg_score), 2)                                      AS avg_perf_score,
       ROUND(AVG(a.total_ot), 1)                                       AS avg_overtime,
       ROUND(AVG(a.abnormal_days), 1)                                  AS avg_abnormal_days,
       ((COUNT(*) * SUM((a.normal_days / NULLIF(a.total_days, 0)) * (p.avg_score)) - SUM(a.normal_days / NULLIF(a.total_days, 0)) * SUM(p.avg_score)) / NULLIF(SQRT((COUNT(*) * SUM((a.normal_days / NULLIF(a.total_days, 0)) * (a.normal_days / NULLIF(a.total_days, 0))) - SUM(a.normal_days / NULLIF(a.total_days, 0)) * SUM(a.normal_days / NULLIF(a.total_days, 0))) * (COUNT(*) * SUM((p.avg_score) * (p.avg_score)) - SUM(p.avg_score) * SUM(p.avg_score))), 0))      AS corr_attendance_score,
       CASE WHEN ((COUNT(*) * SUM((a.normal_days / NULLIF(a.total_days, 0)) * (p.avg_score)) - SUM(a.normal_days / NULLIF(a.total_days, 0)) * SUM(p.avg_score)) / NULLIF(SQRT((COUNT(*) * SUM((a.normal_days / NULLIF(a.total_days, 0)) * (a.normal_days / NULLIF(a.total_days, 0))) - SUM(a.normal_days / NULLIF(a.total_days, 0)) * SUM(a.normal_days / NULLIF(a.total_days, 0))) * (COUNT(*) * SUM((p.avg_score) * (p.avg_score)) - SUM(p.avg_score) * SUM(p.avg_score))), 0)) > 0.3
            THEN 'strong_positive_correlation'
            WHEN ((COUNT(*) * SUM((a.normal_days / NULLIF(a.total_days, 0)) * (p.avg_score)) - SUM(a.normal_days / NULLIF(a.total_days, 0)) * SUM(p.avg_score)) / NULLIF(SQRT((COUNT(*) * SUM((a.normal_days / NULLIF(a.total_days, 0)) * (a.normal_days / NULLIF(a.total_days, 0))) - SUM(a.normal_days / NULLIF(a.total_days, 0)) * SUM(a.normal_days / NULLIF(a.total_days, 0))) * (COUNT(*) * SUM((p.avg_score) * (p.avg_score)) - SUM(p.avg_score) * SUM(p.avg_score))), 0)) < -0.3
            THEN 'negative_correlation'
            ELSE 'weak_correlation' END AS correlation_type
FROM att a
JOIN perf p ON a.emp_id = p.emp_id
GROUP BY CASE WHEN a.normal_days / NULLIF(a.total_days, 0) >= 0.95 THEN 'excellent_attendance'
              WHEN a.normal_days / NULLIF(a.total_days, 0) >= 0.85 THEN 'good_attendance'
              WHEN a.normal_days / NULLIF(a.total_days, 0) >= 0.70 THEN 'fair_attendance'
              ELSE 'poor_attendance' END
ORDER BY avg_perf_score DESC;

-- ------------------------------------------------------------------------------
-- [250] 看板·考勤汇总 | 人力 | 月度考勤综合看板
-- ------------------------------------------------------------------------------
SELECT TO_CHAR(a.att_date, 'YYYY-MM') AS ym,
       COUNT(DISTINCT a.emp_id)                                         AS emp_cnt,
       COUNT(*)                                                         AS total_records,
       ROUND(SUM(CASE WHEN a.status = 'normal' THEN 1 ELSE 0 END)
             / NULLIF(COUNT(*), 0) * 100, 2)                            AS normal_rate_pct,
       ROUND(SUM(CASE WHEN a.status = 'late' THEN 1 ELSE 0 END)
             / NULLIF(COUNT(*), 0) * 100, 2)                            AS late_rate_pct,
       ROUND(SUM(CASE WHEN a.status = 'absent' THEN 1 ELSE 0 END)
             / NULLIF(COUNT(*), 0) * 100, 2)                            AS absent_rate_pct,
       ROUND(SUM(a.overtime_hours), 1)                                  AS total_ot_hours,
       ROUND(AVG(a.work_hours), 2)                                      AS avg_work_hours,
       ROUND(SUM(a.overtime_hours) / NULLIF(COUNT(DISTINCT a.emp_id), 0), 2) AS ot_per_emp,
       ROUND(SUM(a.overtime_hours) - LAG(SUM(a.overtime_hours))
             OVER (ORDER BY TO_CHAR(a.att_date, 'YYYY-MM')), 1)             AS ot_mom_delta
FROM attendance a
WHERE a.att_date >= (TRUNC(SYSDATE) - 365)
GROUP BY TO_CHAR(a.att_date, 'YYYY-MM')
ORDER BY ym DESC;

-- ------------------------------------------------------------------------------
-- [251] 分位·薪酬带宽 | 人力 | 薪酬分位数（P25/P50/P75/P90）与带宽设计
-- ------------------------------------------------------------------------------
WITH s AS (
    SELECT e.job_level, e.salary, e.dept_id,
           ROW_NUMBER() OVER (PARTITION BY e.job_level ORDER BY e.salary) AS rn,
           COUNT(*)     OVER (PARTITION BY e.job_level)                   AS cnt
    FROM employees e
    WHERE e.status = 'active'
)
SELECT job_level,
       COUNT(*)                                                                  AS emp_cnt,
       MAX(CASE WHEN rn <= cnt * 0.25 THEN salary END)                           AS p25,
       MAX(CASE WHEN rn <= cnt * 0.50 THEN salary END)                           AS p50_median,
       MAX(CASE WHEN rn <= cnt * 0.75 THEN salary END)                           AS p75,
       MAX(CASE WHEN rn <= cnt * 0.90 THEN salary END)                           AS p90,
       MIN(salary)                                                               AS minimum,
       MAX(salary)                                                               AS maximum,
       ROUND(MAX(CASE WHEN rn <= cnt * 0.75 THEN salary END)
             / NULLIF(MAX(CASE WHEN rn <= cnt * 0.25 THEN salary END), 0), 2)    AS quartile_ratio,
       ROUND(MAX(salary) / NULLIF(MIN(salary), 0), 2)                            AS max_min_ratio
FROM s
GROUP BY job_level
ORDER BY job_level;

-- ------------------------------------------------------------------------------
-- [252] 轨迹·薪酬增长 | 人力 | 员工薪酬增长轨迹与调薪节奏
-- ------------------------------------------------------------------------------
WITH sal AS (
    SELECT s.emp_id, s.effective_date, s.total_salary,
           LAG(s.total_salary) OVER (PARTITION BY s.emp_id ORDER BY s.effective_date) AS prev_salary,
           ROW_NUMBER() OVER (PARTITION BY s.emp_id ORDER BY s.effective_date)        AS seq,
           COUNT(*)     OVER (PARTITION BY s.emp_id)                                  AS total_records
    FROM salaries s
),
growth AS (
    SELECT emp_id, total_salary, seq, total_records,
           (total_salary - prev_salary) / NULLIF(prev_salary, 0) AS growth_rate,
           FIRST_VALUE(total_salary) OVER (PARTITION BY emp_id ORDER BY seq) AS starting_salary
    FROM sal
)
SELECT seq AS adjustment_seq,
       COUNT(*)                                                      AS emp_cnt,
       ROUND(AVG(growth_rate) * 100, 2)                              AS avg_growth_pct,
       ROUND(AVG(starting_salary), 2)                                AS avg_starting_salary,
       ROUND(AVG(total_salary), 2)                                   AS avg_current_salary,
       ROUND(AVG(total_salary) / NULLIF(AVG(starting_salary), 0), 2) AS cumulative_multiple,
       SUM(CASE WHEN growth_rate < 0 THEN 1 ELSE 0 END)              AS decrease_cnt,
       ROUND(AVG(CASE WHEN growth_rate > 0 THEN growth_rate END) * 100, 2) AS avg_positive_growth_pct
FROM growth
WHERE growth_rate IS NOT NULL
GROUP BY seq
ORDER BY seq;

-- ------------------------------------------------------------------------------
-- [253] 集中·高薪分析 | 人力 | 高薪员工集中度与薪酬差距
-- ------------------------------------------------------------------------------
WITH s AS (
    SELECT e.emp_id, e.emp_name, e.dept_id, e.job_level, e.salary,
           ROW_NUMBER() OVER (ORDER BY e.salary DESC) AS rn,
           COUNT(*) OVER ()                           AS total_emp,
           SUM(e.salary) OVER (ORDER BY e.salary DESC
                ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS cum_salary,
           SUM(e.salary) OVER ()                                   AS total_salary
    FROM employees e
    WHERE e.status = 'active'
)
SELECT CASE WHEN rn <= total_emp * 0.05 THEN 'top_5pct'
            WHEN rn <= total_emp * 0.10 THEN 'top_5_10pct'
            WHEN rn <= total_emp * 0.25 THEN 'top_10_25pct'
            WHEN rn <= total_emp * 0.50 THEN 'top_25_50pct'
            ELSE 'bottom_50pct' END AS salary_tier,
       COUNT(*)                                                          AS emp_cnt,
       ROUND(SUM(salary), 2)                                             AS total_salary,
       ROUND(AVG(salary), 2)                                             AS avg_salary,
       ROUND(SUM(salary) / NULLIF(MAX(total_salary), 0) * 100, 2)        AS salary_share_pct,
       ROUND(AVG(job_level), 2)                                          AS avg_job_level
FROM s
GROUP BY CASE WHEN rn <= total_emp * 0.05 THEN 'top_5pct'
              WHEN rn <= total_emp * 0.10 THEN 'top_5_10pct'
              WHEN rn <= total_emp * 0.25 THEN 'top_10_25pct'
              WHEN rn <= total_emp * 0.50 THEN 'top_25_50pct'
              ELSE 'bottom_50pct' END
ORDER BY avg_salary DESC;

-- ------------------------------------------------------------------------------
-- [254] 效能·人力产出 | 人力 | 人力成本产出比与效能分析
-- ------------------------------------------------------------------------------
WITH cost AS (
    SELECT p.dept_id, SUM(p.gross_pay) AS total_cost, COUNT(DISTINCT p.emp_id) AS headcount
    FROM payroll p
    WHERE p.pay_month >= TO_CHAR((TRUNC(SYSDATE) - 365), 'YYYY-MM')
    GROUP BY p.dept_id
),
proj AS (
    SELECT dept_id, COUNT(*) AS project_cnt, SUM(budget) AS total_budget
    FROM projects GROUP BY dept_id
)
SELECT d.dept_name, d.cost_center,
       c.headcount,
       ROUND(c.total_cost, 2)                                        AS total_cost,
       COALESCE(pr.project_cnt, 0)                                   AS project_cnt,
       ROUND(COALESCE(pr.total_budget, 0), 2)                        AS managed_budget,
       ROUND(COALESCE(pr.total_budget, 0) / NULLIF(c.total_cost, 0), 2) AS budget_per_cost,
       ROUND(COALESCE(pr.total_budget, 0) / NULLIF(c.headcount, 0), 2)  AS budget_per_head,
       ROUND(c.total_cost / NULLIF(c.headcount, 0), 2)               AS cost_per_head,
       RANK() OVER (ORDER BY COALESCE(pr.total_budget, 0) / NULLIF(c.total_cost, 0) DESC) AS efficiency_rank
FROM cost c
JOIN departments d ON c.dept_id = d.dept_id
LEFT JOIN proj pr ON c.dept_id = pr.dept_id
ORDER BY budget_per_cost DESC;

-- ------------------------------------------------------------------------------
-- [255] 编制·执行率 | 人力 | 编制执行率与招聘缺口分析
-- ------------------------------------------------------------------------------
WITH req AS (
    SELECT dept_id, status, SUM(headcount) AS planned_headcount
    FROM recruitment
    GROUP BY dept_id, status
),
act AS (
    SELECT dept_id, COUNT(*) AS actual_headcount
    FROM employees WHERE status = 'active' GROUP BY dept_id
)
SELECT d.dept_name,
       COALESCE(a.actual_headcount, 0)                                        AS actual_headcount,
       COALESCE(SUM(r.planned_headcount), 0)                                  AS planned_headcount,
       COALESCE(SUM(CASE WHEN r.status = 'open' THEN r.planned_headcount END), 0) AS open_headcount,
       ROUND(COALESCE(a.actual_headcount, 0)
             / NULLIF(COALESCE(a.actual_headcount, 0) + COALESCE(SUM(CASE WHEN r.status = 'open' THEN r.planned_headcount END), 0), 0) * 100, 2) AS fill_rate_pct,
       COALESCE(SUM(CASE WHEN r.status = 'open' THEN r.planned_headcount END), 0) AS hiring_gap,
       CASE WHEN COALESCE(SUM(CASE WHEN r.status = 'open' THEN r.planned_headcount END), 0)
                 > COALESCE(a.actual_headcount, 0) * 0.2 THEN 'understaffed'
            WHEN COALESCE(SUM(CASE WHEN r.status = 'open' THEN r.planned_headcount END), 0) = 0 THEN 'fully_staffed'
            ELSE 'normal_hiring' END AS staffing_status
FROM departments d
LEFT JOIN req r ON d.dept_id = r.dept_id
LEFT JOIN act a ON d.dept_id = a.dept_id
GROUP BY d.dept_name, a.actual_headcount
ORDER BY hiring_gap DESC;

-- ------------------------------------------------------------------------------
-- [256] 效能·人均产出 | 人力 | 组织人均产出与效能对比
-- ------------------------------------------------------------------------------
WITH hc AS (
    SELECT dept_id, COUNT(*) AS headcount, AVG(salary) AS avg_salary
    FROM employees WHERE status = 'active' GROUP BY dept_id
),
emp_load AS (
    SELECT pa.emp_id, COUNT(DISTINCT pa.project_id) AS project_cnt,
           SUM(pa.allocation_pct) AS total_allocation
    FROM project_assignments pa
    GROUP BY pa.emp_id
),
dept_load AS (
    SELECT e.dept_id, AVG(l.project_cnt) AS avg_projects, AVG(l.total_allocation) AS avg_allocation
    FROM emp_load l
    JOIN employees e ON l.emp_id = e.emp_id
    GROUP BY e.dept_id
)
SELECT d.dept_name,
       hc.headcount,
       ROUND(hc.avg_salary, 2)                                    AS avg_salary,
       ROUND(COALESCE(dl.avg_projects, 0), 2)                     AS avg_projects_per_emp,
       ROUND(COALESCE(dl.avg_allocation, 0), 1)                   AS avg_allocation_pct,
       ROUND(COALESCE(dl.avg_allocation, 0) / NULLIF(hc.avg_salary, 0) * 1000, 2) AS output_per_10k_salary,
       CASE WHEN COALESCE(dl.avg_allocation, 0) > 100 THEN 'over_allocated'
            WHEN COALESCE(dl.avg_allocation, 0) < 60 THEN 'under_utilized'
            ELSE 'optimal' END AS utilization_status
FROM hc
JOIN departments d ON hc.dept_id = d.dept_id
LEFT JOIN dept_load dl ON hc.dept_id = dl.dept_id
ORDER BY output_per_10k_salary DESC;

-- ------------------------------------------------------------------------------
-- [257] 画像·员工综合 | 人力 | 员工综合画像（绩效+薪酬+司龄+考勤）
-- ------------------------------------------------------------------------------
WITH perf AS (
    SELECT emp_id, AVG(score) AS avg_score, COUNT(*) AS review_cnt
    FROM performance GROUP BY emp_id
),
att AS (
    SELECT emp_id,
           ROUND(SUM(CASE WHEN status = 'normal' THEN 1 ELSE 0 END)
                 / NULLIF(COUNT(*), 0) * 100, 2) AS attendance_rate,
           SUM(overtime_hours) AS total_ot
    FROM attendance
    WHERE att_date >= (TRUNC(SYSDATE) - 180)
    GROUP BY emp_id
),
tr AS (
    SELECT emp_id, COUNT(*) AS training_cnt, AVG(score) AS avg_training_score
    FROM training_records WHERE status = 'completed'
    GROUP BY emp_id
)
SELECT e.emp_id, e.emp_name, e.position, e.job_level, d.dept_name,
       ROUND(p.avg_score, 2)                            AS perf_score,
       a.attendance_rate,
       ROUND(a.total_ot, 1)                             AS overtime_hours,
       COALESCE(t.training_cnt, 0)                      AS training_cnt,
       ROUND(t.avg_training_score, 2)                   AS training_score,
       ROUND((TRUNC(SYSDATE) - e.hire_date) / 365.0, 2) AS tenure_years,
       e.salary,
       CASE WHEN p.avg_score >= 90 AND a.attendance_rate >= 95 THEN 'star_employee'
            WHEN p.avg_score >= 80 AND COALESCE(t.training_cnt, 0) >= 3 THEN 'developing_talent'
            WHEN p.avg_score < 70 OR a.attendance_rate < 85 THEN 'needs_attention'
            ELSE 'solid' END AS employee_tag
FROM employees e
LEFT JOIN perf p       ON e.emp_id = p.emp_id
LEFT JOIN att  a       ON e.emp_id = a.emp_id
LEFT JOIN tr   t       ON e.emp_id = t.emp_id
LEFT JOIN departments d ON e.dept_id = d.dept_id
WHERE e.status = 'active'
ORDER BY p.avg_score DESC
FETCH FIRST 500 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [258] 协作·跨部门 | 人力 | 跨部门项目协作网络分析
-- ------------------------------------------------------------------------------
WITH pa AS (
    SELECT p.project_id, p.dept_id AS owner_dept, e.dept_id AS member_dept, pa.emp_id
    FROM project_assignments pa
    JOIN projects  p ON pa.project_id = p.project_id
    JOIN employees e ON pa.emp_id = e.emp_id
),
cross_dept AS (
    SELECT owner_dept, member_dept,
           COUNT(DISTINCT project_id) AS joint_projects,
           COUNT(DISTINCT emp_id)     AS involved_emp
    FROM pa
    WHERE owner_dept <> member_dept
    GROUP BY owner_dept, member_dept
)
SELECT d1.dept_name AS owner_department,
       d2.dept_name AS participating_department,
       cd.joint_projects, cd.involved_emp,
       RANK() OVER (ORDER BY cd.joint_projects DESC) AS collaboration_rank,
       ROUND(cd.joint_projects / NULLIF(SUM(cd.joint_projects) OVER (), 0) * 100, 2) AS collaboration_share_pct,
       CASE WHEN cd.joint_projects >= 5 THEN 'strong_tie'
            WHEN cd.joint_projects >= 2 THEN 'moderate_tie'
            ELSE 'weak_tie' END AS tie_strength
FROM cross_dept cd
JOIN departments d1 ON cd.owner_dept = d1.dept_id
JOIN departments d2 ON cd.member_dept = d2.dept_id
ORDER BY cd.joint_projects DESC
FETCH FIRST 100 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [259] 团队·管理者分析 | 人力 | 管理者团队构成与团队健康度
-- ------------------------------------------------------------------------------
WITH team AS (
    SELECT e.manager_id,
           COUNT(*)                                                    AS team_size,
           AVG(e.salary)                                               AS avg_team_salary,
           AVG((TRUNC(SYSDATE) - e.hire_date) / 365.0)             AS avg_team_tenure,
           COUNT(DISTINCT e.job_level)                                 AS level_diversity,
           COUNT(DISTINCT e.dept_id)                                   AS dept_diversity,
           SUM(CASE WHEN e.status = 'inactive' THEN 1 ELSE 0 END)      AS attrition_cnt
    FROM employees e
    WHERE e.manager_id IS NOT NULL
    GROUP BY e.manager_id
),
mgr_perf AS (
    SELECT mgr.manager_id, mgr.team_size, mgr.avg_team_salary, mgr.avg_team_tenure,
           mgr.level_diversity, mgr.attrition_cnt,
           p.avg_score AS mgr_score
    FROM team mgr
    LEFT JOIN (SELECT emp_id, AVG(score) AS avg_score FROM performance GROUP BY emp_id) p
           ON mgr.manager_id = p.emp_id
)
SELECT mgr.manager_id, m.emp_name AS manager_name,
       mgr.team_size,
       ROUND(mgr.avg_team_salary, 2)                                   AS avg_team_salary,
       ROUND(mgr.avg_team_tenure, 2)                                   AS avg_team_tenure,
       mgr.level_diversity, mgr.attrition_cnt,
       ROUND(mgr.mgr_score, 2)                                         AS manager_score,
       ROUND(mgr.attrition_cnt / NULLIF(mgr.team_size, 0) * 100, 2)    AS team_attrition_pct,
       CASE WHEN mgr.team_size < 3 THEN 'undersized_team'
            WHEN mgr.team_size > 15 THEN 'oversized_team'
            WHEN mgr.attrition_cnt / NULLIF(mgr.team_size, 0) > 0.3 THEN 'high_attrition_team'
            ELSE 'healthy_team' END AS team_health
FROM mgr_perf mgr
JOIN employees m ON mgr.manager_id = m.emp_id
ORDER BY team_size DESC;

-- ------------------------------------------------------------------------------
-- [260] 看板·人力全景 | 人力 | 人力资源全景看板（规模/成本/效能）
-- ------------------------------------------------------------------------------
WITH hc AS (
    SELECT COUNT(*) AS total_emp,
           SUM(CASE WHEN status = 'active' THEN 1 ELSE 0 END) AS active_emp,
           SUM(CASE WHEN leave_date >= (TRUNC(SYSDATE) - 365) THEN 1 ELSE 0 END) AS left_1y,
           ROUND(AVG(salary), 2) AS avg_salary,
           ROUND(AVG((TRUNC(SYSDATE) - hire_date) / 365.0), 2) AS avg_tenure
    FROM employees
),
cost AS (
    SELECT ROUND(SUM(gross_pay), 2) AS annual_cost
    FROM payroll
    WHERE pay_month >= TO_CHAR((TRUNC(SYSDATE) - 365), 'YYYY-MM')
),
perf AS (
    SELECT ROUND(AVG(score), 2) AS avg_perf_score
    FROM performance
),
att AS (
    SELECT ROUND(SUM(CASE WHEN status = 'normal' THEN 1 ELSE 0 END)
                 / NULLIF(COUNT(*), 0) * 100, 2) AS attendance_rate
    FROM attendance
    WHERE att_date >= (TRUNC(SYSDATE) - 90)
)
SELECT hc.total_emp, hc.active_emp, hc.left_1y,
       ROUND(hc.left_1y / NULLIF(hc.active_emp, 0) * 100, 2) AS turnover_pct,
       hc.avg_salary, hc.avg_tenure,
       cost.annual_cost,
       ROUND(cost.annual_cost / NULLIF(hc.active_emp, 0), 2) AS cost_per_head,
       perf.avg_perf_score,
       att.attendance_rate
FROM hc
CROSS JOIN cost
CROSS JOIN perf
CROSS JOIN att;

-- ------------------------------------------------------------------------------
-- [261] 漏斗·招聘转化 | 人力 | 招聘漏斗各环节转化率与瓶颈识别
-- ------------------------------------------------------------------------------
WITH req AS (
    SELECT r.dept_id,
           SUM(r.apply_cnt)  AS apply_cnt,
           SUM(r.offer_cnt)  AS offer_cnt,
           SUM(r.hired_cnt)  AS hired_cnt,
           SUM(r.headcount)  AS planned_cnt
    FROM recruitment r
    WHERE r.open_date >= (TRUNC(SYSDATE) - 365)
    GROUP BY r.dept_id
)
SELECT d.dept_name,
       r.apply_cnt, r.offer_cnt, r.hired_cnt, r.planned_cnt,
       ROUND(r.offer_cnt * 100.0 / NULLIF(r.apply_cnt, 0), 2)  AS offer_rate_pct,
       ROUND(r.hired_cnt * 100.0 / NULLIF(r.offer_cnt, 0), 2)  AS accept_rate_pct,
       ROUND(r.hired_cnt * 100.0 / NULLIF(r.apply_cnt, 0), 2)  AS overall_rate_pct,
       ROUND(r.hired_cnt * 100.0 / NULLIF(r.planned_cnt, 0), 2) AS fill_rate_pct,
       CASE WHEN r.offer_cnt * 1.0 / NULLIF(r.apply_cnt, 0) < 0.05 THEN 'bottleneck_screening'
            WHEN r.hired_cnt * 1.0 / NULLIF(r.offer_cnt, 0) < 0.60 THEN 'bottleneck_offer'
            WHEN r.hired_cnt * 1.0 / NULLIF(r.planned_cnt, 0) < 0.80 THEN 'bottleneck_volume'
            ELSE 'healthy' END AS bottleneck_stage,
       DENSE_RANK() OVER (ORDER BY r.hired_cnt * 1.0 / NULLIF(r.apply_cnt, 0) DESC) AS funnel_rank
FROM req r
JOIN departments d ON r.dept_id = d.dept_id
ORDER BY overall_rate_pct DESC;

-- ------------------------------------------------------------------------------
-- [262] 周期·招聘时效 | 人力 | 招聘需求关闭周期与积压情况分析
-- ------------------------------------------------------------------------------
SELECT TO_CHAR(r.open_date, 'YYYY-MM') AS open_ym,
       r.position,
       COUNT(*)                                                    AS req_cnt,
       SUM(CASE WHEN r.status = 'closed' THEN 1 ELSE 0 END)        AS closed_cnt,
       ROUND(SUM(CASE WHEN r.status = 'closed' THEN 1 ELSE 0 END) * 100.0
             / NULLIF(COUNT(*), 0), 2)                             AS close_rate_pct,
       ROUND(AVG((COALESCE(r.close_date, TRUNC(SYSDATE)) - r.open_date)), 1) AS avg_days_open,
       ROUND(MAX((COALESCE(r.close_date, TRUNC(SYSDATE)) - r.open_date)), 1) AS max_days_open,
       SUM(CASE WHEN r.status = 'open'
                 AND (TRUNC(SYSDATE) - r.open_date) > 60 THEN 1 ELSE 0 END) AS backlog_over_60d,
       SUM(r.hired_cnt)                                            AS hired_cnt
FROM recruitment r
WHERE r.open_date >= (TRUNC(SYSDATE) - 730)
GROUP BY TO_CHAR(r.open_date, 'YYYY-MM'), r.position
ORDER BY open_ym DESC, backlog_over_60d DESC;

-- ------------------------------------------------------------------------------
-- [263] 渠道·招聘来源 | 人力 | 招聘渠道效果与成本效益对比
-- ------------------------------------------------------------------------------
WITH ch AS (
    SELECT r.channel,
           COUNT(*)                                   AS req_cnt,
           SUM(r.apply_cnt)                           AS apply_cnt,
           SUM(r.hired_cnt)                           AS hired_cnt,
           SUM(COALESCE(r.channel_cost, 0))           AS channel_cost
    FROM recruitment r
    WHERE r.open_date >= (TRUNC(SYSDATE) - 365)
    GROUP BY r.channel
)
SELECT channel, req_cnt, apply_cnt, hired_cnt,
       ROUND(channel_cost, 2)                                        AS total_cost,
       ROUND(channel_cost / NULLIF(hired_cnt, 0), 2)                 AS cost_per_hire,
       ROUND(hired_cnt * 100.0 / NULLIF(apply_cnt, 0), 2)            AS hire_rate_pct,
       ROUND(hired_cnt * 100.0 / NULLIF(SUM(hired_cnt) OVER (), 0), 2) AS hire_share_pct,
       RANK() OVER (ORDER BY channel_cost / NULLIF(hired_cnt, 0))    AS cost_rank,
       CASE WHEN channel_cost / NULLIF(hired_cnt, 0) < 3000
                 AND hired_cnt * 1.0 / NULLIF(apply_cnt, 0) > 0.10 THEN 'high_efficiency'
            WHEN channel_cost / NULLIF(hired_cnt, 0) > 12000 THEN 'high_cost'
            WHEN hired_cnt = 0 THEN 'no_conversion'
            ELSE 'average' END AS channel_grade
FROM ch
ORDER BY cost_per_hire;

-- ------------------------------------------------------------------------------
-- [264] 薪酬·Offer竞争 | 人力 | Offer 薪资竞争力与接受率关联分析
-- ------------------------------------------------------------------------------
WITH off AS (
    SELECT r.dept_id, r.position,
           SUM(r.offer_cnt)                                   AS offer_cnt,
           SUM(r.hired_cnt)                                   AS accepted_cnt,
           ROUND(AVG(r.offer_salary), 2)                      AS avg_offer_salary
    FROM recruitment r
    WHERE r.open_date >= (TRUNC(SYSDATE) - 365)
    GROUP BY r.dept_id, r.position
),
mk AS (
    SELECT e.dept_id, e.position, ROUND(AVG(e.salary), 2) AS internal_avg_salary
    FROM employees e
    WHERE e.status = 'active'
    GROUP BY e.dept_id, e.position
)
SELECT d.dept_name, o.position, o.offer_cnt, o.accepted_cnt,
       o.avg_offer_salary, m.internal_avg_salary,
       ROUND(o.avg_offer_salary / NULLIF(m.internal_avg_salary, 0), 3) AS offer_compa_ratio,
       ROUND(o.accepted_cnt * 100.0 / NULLIF(o.offer_cnt, 0), 2)       AS accept_rate_pct,
       CASE WHEN o.avg_offer_salary / NULLIF(m.internal_avg_salary, 0) < 0.95 THEN 'below_market_risk'
            WHEN o.avg_offer_salary / NULLIF(m.internal_avg_salary, 0) > 1.20 THEN 'salary_inversion_risk'
            ELSE 'competitive' END AS salary_position_flag,
       NTILE(4) OVER (ORDER BY o.accepted_cnt * 1.0 / NULLIF(o.offer_cnt, 0)) AS accept_quartile
FROM off o
JOIN departments d ON o.dept_id = d.dept_id
LEFT JOIN mk m ON o.dept_id = m.dept_id AND o.position = m.position
ORDER BY accept_rate_pct DESC;

-- ------------------------------------------------------------------------------
-- [265] 留存·试用期 | 人力 | 新员工试用期通过率与入职来源质量
-- ------------------------------------------------------------------------------
WITH nh AS (
    SELECT e.emp_id, e.dept_id, e.hire_date,
           (TRUNC(SYSDATE) - e.hire_date) AS days_since_hire,
           p.avg_score
    FROM employees e
    LEFT JOIN (SELECT emp_id, AVG(score) AS avg_score FROM performance GROUP BY emp_id) p
           ON e.emp_id = p.emp_id
    WHERE e.hire_date >= (TRUNC(SYSDATE) - 365)
)
SELECT d.dept_name,
       COUNT(*)                                                        AS new_hires,
       SUM(CASE WHEN n.avg_score >= 70 THEN 1 ELSE 0 END)              AS passed_probation,
       ROUND(SUM(CASE WHEN n.avg_score >= 70 THEN 1 ELSE 0 END) * 100.0
             / NULLIF(COUNT(*), 0), 2)                                 AS pass_rate_pct,
       SUM(CASE WHEN n.days_since_hire >= 90 AND e2.status = 'left' THEN 1 ELSE 0 END) AS left_in_90d,
       ROUND(SUM(CASE WHEN n.days_since_hire >= 90 AND e2.status = 'left' THEN 1 ELSE 0 END) * 100.0
             / NULLIF(COUNT(*), 0), 2)                                 AS early_attrition_pct,
       ROUND(AVG(n.avg_score), 2)                                      AS avg_perf_score,
       ROUND(AVG(n.days_since_hire), 0)                                AS avg_tenure_days
FROM nh n
JOIN employees e2 ON n.emp_id = e2.emp_id
JOIN departments d ON n.dept_id = d.dept_id
GROUP BY d.dept_name
ORDER BY pass_rate_pct, early_attrition_pct DESC;

-- ------------------------------------------------------------------------------
-- [266] 培训·覆盖完成 | 人力 | 培训覆盖率、完成率与学时结构分析
-- ------------------------------------------------------------------------------
WITH tr AS (
    SELECT t.emp_id,
           COUNT(*)                                                       AS enroll_cnt,
           SUM(CASE WHEN t.status = 'completed' THEN 1 ELSE 0 END)        AS done_cnt,
           SUM(CASE WHEN t.status = 'failed' THEN 1 ELSE 0 END)           AS fail_cnt,
           SUM(COALESCE(t.hours, 0))                                      AS total_hours,
           SUM(COALESCE(t.cost, 0))                                       AS total_cost
    FROM training_records t
    WHERE t.train_date >= (TRUNC(SYSDATE) - 365)
    GROUP BY t.emp_id
),
dept AS (
    SELECT e.dept_id, COUNT(*) AS dept_emp
    FROM employees e WHERE e.status = 'active' GROUP BY e.dept_id
)
SELECT d.dept_name, dp.dept_emp,
       COUNT(t.emp_id)                                                   AS trained_emp,
       ROUND(COUNT(t.emp_id) * 100.0 / NULLIF(dp.dept_emp, 0), 2)        AS coverage_pct,
       SUM(COALESCE(t.done_cnt, 0))                                      AS completed_courses,
       SUM(COALESCE(t.enroll_cnt, 0))                                    AS enrolled_courses,
       ROUND(SUM(COALESCE(t.done_cnt, 0)) * 100.0
             / NULLIF(SUM(COALESCE(t.enroll_cnt, 0)), 0), 2)             AS completion_rate_pct,
       ROUND(SUM(COALESCE(t.total_hours, 0)) / NULLIF(COUNT(t.emp_id), 0), 1) AS avg_hours_per_emp,
       ROUND(SUM(COALESCE(t.total_cost, 0)) / NULLIF(dp.dept_emp, 0), 2) AS cost_per_emp,
       RANK() OVER (ORDER BY SUM(COALESCE(t.done_cnt, 0)) * 1.0
                             / NULLIF(SUM(COALESCE(t.enroll_cnt, 0)), 0) DESC) AS completion_rank
FROM employees e2
JOIN dept dp ON e2.dept_id = dp.dept_id
JOIN departments d ON dp.dept_id = d.dept_id
LEFT JOIN tr t ON e2.emp_id = t.emp_id
WHERE e2.status = 'active'
GROUP BY d.dept_name, dp.dept_emp
ORDER BY coverage_pct DESC;

-- ------------------------------------------------------------------------------
-- [267] 关联·培训绩效 | 人力 | 培训投入与绩效提升的关联分析
-- ------------------------------------------------------------------------------
WITH tr AS (
    SELECT emp_id,
           COUNT(*)                                            AS train_cnt,
           SUM(COALESCE(hours, 0))                             AS total_hours,
           ROUND(AVG(score), 2)                                AS avg_train_score
    FROM training_records
    WHERE status = 'completed'
    GROUP BY emp_id
),
pf AS (
    SELECT emp_id, AVG(score) AS perf_score
    FROM performance GROUP BY emp_id
)
SELECT CASE WHEN COALESCE(t.train_cnt, 0) = 0 THEN 'no_training'
            WHEN t.train_cnt <= 2 THEN 'light_1_2'
            WHEN t.train_cnt <= 5 THEN 'moderate_3_5'
            ELSE 'heavy_6plus' END AS training_level,
       COUNT(*)                                                    AS emp_cnt,
       ROUND(AVG(p.perf_score), 2)                                 AS avg_perf_score,
       ROUND(AVG(COALESCE(t.total_hours, 0)), 1)                   AS avg_hours,
       ROUND(((COUNT(*) * SUM((COALESCE(t.total_hours, 0)) * (p.perf_score)) - SUM(COALESCE(t.total_hours, 0)) * SUM(p.perf_score)) / NULLIF(SQRT((COUNT(*) * SUM((COALESCE(t.total_hours, 0)) * (COALESCE(t.total_hours, 0))) - SUM(COALESCE(t.total_hours, 0)) * SUM(COALESCE(t.total_hours, 0))) * (COUNT(*) * SUM((p.perf_score) * (p.perf_score)) - SUM(p.perf_score) * SUM(p.perf_score))), 0)), 4)  AS corr_hours_perf,
       ROUND(AVG(p.perf_score) - AVG(AVG(p.perf_score)) OVER (), 2) AS perf_vs_overall
FROM employees e
JOIN pf p ON e.emp_id = p.emp_id
LEFT JOIN tr t ON e.emp_id = t.emp_id
WHERE e.status = 'active'
GROUP BY CASE WHEN COALESCE(t.train_cnt, 0) = 0 THEN 'no_training'
              WHEN t.train_cnt <= 2 THEN 'light_1_2'
              WHEN t.train_cnt <= 5 THEN 'moderate_3_5'
              ELSE 'heavy_6plus' END
ORDER BY avg_perf_score DESC;

-- ------------------------------------------------------------------------------
-- [268] 投入·培训产出 | 人力 | 培训成本投入与人均产出效益评估
-- ------------------------------------------------------------------------------
WITH cost AS (
    SELECT e.dept_id,
           SUM(COALESCE(t.cost, 0))                        AS training_cost,
           SUM(COALESCE(t.hours, 0))                       AS training_hours,
           COUNT(DISTINCT t.emp_id)                         AS trained_emp
    FROM training_records t
    JOIN employees e ON t.emp_id = e.emp_id
    WHERE t.train_date >= (TRUNC(SYSDATE) - 365)
    GROUP BY e.dept_id
),
pay AS (
    SELECT e.dept_id, SUM(p.gross_pay) AS annual_payroll
    FROM payroll p
    JOIN employees e ON p.emp_id = e.emp_id
    WHERE p.pay_month >= TO_CHAR((TRUNC(SYSDATE) - 365), 'YYYY-MM')
    GROUP BY e.dept_id
)
SELECT d.dept_name,
       ROUND(c.training_cost, 2)                                            AS training_cost,
       c.trained_emp,
       ROUND(c.training_cost / NULLIF(c.trained_emp, 0), 2)                 AS cost_per_trained,
       ROUND(c.training_hours / NULLIF(c.trained_emp, 0), 1)                AS hours_per_trained,
       ROUND(py.annual_payroll, 2)                                          AS annual_payroll,
       ROUND(c.training_cost * 100.0 / NULLIF(py.annual_payroll, 0), 2)     AS training_invest_ratio,
       CASE WHEN c.training_cost * 1.0 / NULLIF(py.annual_payroll, 0) < 0.01 THEN 'under_invested'
            WHEN c.training_cost * 1.0 / NULLIF(py.annual_payroll, 0) > 0.05 THEN 'over_invested'
            ELSE 'reasonable' END AS investment_flag,
       PERCENT_RANK() OVER (ORDER BY c.training_cost * 1.0
                                     / NULLIF(py.annual_payroll, 0))        AS invest_percentile
FROM cost c
JOIN departments d ON c.dept_id = d.dept_id
LEFT JOIN pay py ON c.dept_id = py.dept_id
ORDER BY training_invest_ratio DESC;

-- ------------------------------------------------------------------------------
-- [269] 项目·人力投入 | 人力 | 项目人力投入结构与成本分摊分析
-- ------------------------------------------------------------------------------
WITH pa AS (
    SELECT pa.project_id,
           COUNT(DISTINCT pa.emp_id)                          AS member_cnt,
           ROUND(SUM(pa.allocation_pct), 1)                   AS total_allocation,
           ROUND(AVG(pa.allocation_pct), 1)                   AS avg_allocation
    FROM project_assignments pa
    GROUP BY pa.project_id
),
pc AS (
    SELECT pa.project_id,
           ROUND(SUM(e.salary * pa.allocation_pct / 100.0), 2) AS labor_cost
    FROM project_assignments pa
    JOIN employees e ON pa.emp_id = e.emp_id
    GROUP BY pa.project_id
)
SELECT p.project_name, p.status, d.dept_name,
       COALESCE(a.member_cnt, 0)                                        AS member_cnt,
       COALESCE(a.total_allocation, 0)                                  AS total_allocation,
       ROUND(c.labor_cost, 2)                                           AS labor_cost,
       COALESCE(p.budget, 0)                                            AS budget,
       ROUND(c.labor_cost * 100.0 / NULLIF(p.budget, 0), 2)             AS budget_usage_pct,
       ROUND(COALESCE(p.budget, 0) - c.labor_cost, 2)                   AS budget_remaining,
       ROUND((COALESCE(p.end_date, TRUNC(SYSDATE)) - p.start_date) / 30.0, 1) AS duration_months,
       CASE WHEN c.labor_cost > COALESCE(p.budget, 0) THEN 'over_budget'
            WHEN c.labor_cost * 1.0 / NULLIF(p.budget, 0) > 0.85 THEN 'budget_warning'
            WHEN COALESCE(a.member_cnt, 0) < 3 THEN 'under_staffed'
            ELSE 'on_track' END AS project_health
FROM projects p
JOIN departments d ON p.dept_id = d.dept_id
LEFT JOIN pa a ON p.project_id = a.project_id
LEFT JOIN pc c ON p.project_id = c.project_id
ORDER BY budget_usage_pct DESC;

-- ------------------------------------------------------------------------------
-- [270] 负荷·并行冲突 | 人力 | 员工并行项目负荷与资源冲突识别
-- ------------------------------------------------------------------------------
WITH emp_load AS (
    SELECT pa.emp_id,
           COUNT(DISTINCT pa.project_id)                      AS project_cnt,
           SUM(pa.allocation_pct)                             AS total_alloc,
           LISTAGG(CAST(pa.project_id AS VARCHAR(50)), ',') WITHIN GROUP (ORDER BY pa.project_id)  AS project_list
    FROM project_assignments pa
    JOIN projects p ON pa.project_id = p.project_id
    WHERE p.status IN ('active', 'planning')
    GROUP BY pa.emp_id
)
SELECT e.emp_id, e.emp_name, e.position, d.dept_name,
       l.project_cnt, l.total_alloc, l.project_list,
       ROUND(l.total_alloc / NULLIF(l.project_cnt, 0), 1) AS avg_alloc_per_project,
       CASE WHEN l.total_alloc > 150 THEN 'severe_overload'
            WHEN l.total_alloc > 100 THEN 'over_allocated'
            WHEN l.total_alloc < 50 THEN 'under_utilized'
            ELSE 'balanced' END AS load_status,
       RANK() OVER (PARTITION BY e.dept_id ORDER BY l.total_alloc DESC) AS dept_load_rank,
       ROUND(l.total_alloc - 100.0, 1) AS alloc_gap
FROM emp_load l
JOIN employees e ON l.emp_id = e.emp_id
JOIN departments d ON e.dept_id = d.dept_id
WHERE e.status = 'active'
ORDER BY l.total_alloc DESC
FETCH FIRST 200 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [271] 进度·项目配置 | 人力 | 项目周期、人力配置与交付效率对比
-- ------------------------------------------------------------------------------
WITH pm AS (
    SELECT p.project_id, p.project_name, p.dept_id, p.status,
           (COALESCE(p.end_date, TRUNC(SYSDATE)) - p.start_date) AS duration_days,
           (SELECT COUNT(*) FROM project_assignments pa WHERE pa.project_id = p.project_id) AS member_cnt,
           (SELECT COALESCE(SUM(pa2.allocation_pct), 0) FROM project_assignments pa2
            WHERE pa2.project_id = p.project_id) AS total_alloc
    FROM projects p
    WHERE p.start_date >= (TRUNC(SYSDATE) - 730)
)
SELECT d.dept_name, m.status,
       COUNT(*)                                                    AS project_cnt,
       ROUND(AVG(m.duration_days), 1)                              AS avg_duration_days,
       ROUND(AVG(m.member_cnt), 1)                                 AS avg_team_size,
       ROUND(AVG(m.total_alloc), 1)                                AS avg_total_alloc,
       ROUND(AVG(m.duration_days) / NULLIF(AVG(m.member_cnt), 0), 1) AS days_per_member,
       SUM(CASE WHEN m.duration_days > 365 THEN 1 ELSE 0 END)      AS long_running_cnt,
       ROUND(SUM(CASE WHEN m.duration_days > 365 THEN 1 ELSE 0 END) * 100.0
             / NULLIF(COUNT(*), 0), 2)                             AS long_running_pct,
       ROUND(AVG(m.duration_days)
             - AVG(AVG(m.duration_days)) OVER (), 1)               AS duration_vs_avg
FROM pm m
JOIN departments d ON m.dept_id = d.dept_id
GROUP BY d.dept_name, m.status
ORDER BY avg_duration_days DESC;

-- ------------------------------------------------------------------------------
-- [272] 梯队·继任计划 | 人力 | 关键岗位继任梯队与就绪度评估
-- ------------------------------------------------------------------------------
WITH key_pos AS (
    SELECT e.dept_id, e.position, COUNT(*) AS holder_cnt,
           ROUND(AVG(e.salary), 2) AS avg_salary,
           MAX(e.job_level) AS max_level
    FROM employees e
    WHERE e.status = 'active' AND e.job_level >= 4
    GROUP BY e.dept_id, e.position
),
cand AS (
    SELECT e.dept_id, e.position, e.emp_id, e.emp_name, e.job_level,
           p.avg_score,
           (TRUNC(SYSDATE) - e.hire_date) / 365.0 AS tenure_years,
           ROW_NUMBER() OVER (PARTITION BY e.dept_id, e.position
                              ORDER BY p.avg_score DESC, e.job_level DESC) AS cand_rn
    FROM employees e
    LEFT JOIN (SELECT emp_id, AVG(score) AS avg_score FROM performance GROUP BY emp_id) p
           ON e.emp_id = p.emp_id
    WHERE e.status = 'active' AND e.job_level < 4
)
SELECT k.position, d.dept_name, k.holder_cnt, k.avg_salary,
       c.emp_name                                       AS successor_candidate,
       ROUND(c.avg_score, 2)                            AS candidate_score,
       ROUND(c.tenure_years, 2)                         AS candidate_tenure,
       CASE WHEN c.avg_score >= 85 AND c.tenure_years >= 3 THEN 'ready_now'
            WHEN c.avg_score >= 80 THEN 'ready_1_year'
            WHEN c.avg_score >= 70 THEN 'ready_2_years'
            WHEN c.emp_id IS NULL THEN 'no_successor'
            ELSE 'not_ready' END AS readiness,
       CASE WHEN c.emp_id IS NULL THEN 'critical_gap'
            WHEN k.holder_cnt = 1 AND c.avg_score < 80 THEN 'high_risk'
            ELSE 'covered' END AS succession_risk
FROM key_pos k
JOIN departments d ON k.dept_id = d.dept_id
LEFT JOIN cand c ON k.dept_id = c.dept_id AND k.position = c.position AND c.cand_rn = 1
ORDER BY succession_risk, k.avg_salary DESC;

-- ------------------------------------------------------------------------------
-- [273] 流动·内部轮岗 | 人力 | 内部岗位流动、轮岗与晋升活跃度分析
-- ------------------------------------------------------------------------------
WITH chg AS (
    SELECT c.emp_id, c.change_type, c.change_date,
           c.old_dept_id, c.new_dept_id,
           ROW_NUMBER() OVER (PARTITION BY c.emp_id ORDER BY c.change_date) AS chg_seq,
           COUNT(*) OVER (PARTITION BY c.emp_id) AS chg_total
    FROM emp_changes c
    WHERE c.change_date >= (TRUNC(SYSDATE) - 1095)
)
SELECT d.dept_name,
       COUNT(DISTINCT c.emp_id)                                          AS moved_emp,
       SUM(CASE WHEN c.change_type = 'transfer' THEN 1 ELSE 0 END)        AS transfer_cnt,
       SUM(CASE WHEN c.change_type = 'promotion' THEN 1 ELSE 0 END)       AS promotion_cnt,
       SUM(CASE WHEN c.old_dept_id <> c.new_dept_id THEN 1 ELSE 0 END)    AS cross_dept_cnt,
       ROUND(SUM(CASE WHEN c.old_dept_id <> c.new_dept_id THEN 1 ELSE 0 END) * 100.0
             / NULLIF(COUNT(*), 0), 2)                                    AS cross_dept_pct,
       ROUND(AVG(c.chg_total), 2)                                         AS avg_moves_per_emp,
       (SELECT COUNT(*) FROM employees e2
        WHERE e2.dept_id = d.dept_id AND e2.status = 'active')            AS headcount,
       ROUND(COUNT(DISTINCT c.emp_id) * 100.0
             / NULLIF((SELECT COUNT(*) FROM employees e3
                       WHERE e3.dept_id = d.dept_id AND e3.status = 'active'), 0), 2) AS mobility_rate_pct
FROM chg c
JOIN departments d ON c.old_dept_id = d.dept_id
GROUP BY d.dept_name, d.dept_id
ORDER BY mobility_rate_pct DESC;

-- ------------------------------------------------------------------------------
-- [274] 晋升·速度分析 | 人力 | 晋升速度、职级跃迁与停滞识别
-- ------------------------------------------------------------------------------
WITH promo AS (
    SELECT c.emp_id, c.change_date, c.old_salary, c.new_salary,
           ROW_NUMBER() OVER (PARTITION BY c.emp_id ORDER BY c.change_date) AS promo_seq,
           LAG(c.change_date) OVER (PARTITION BY c.emp_id ORDER BY c.change_date) AS prev_promo_date
    FROM emp_changes c
    WHERE c.change_type = 'promotion'
),
gap AS (
    SELECT emp_id, promo_seq,
           (change_date - prev_promo_date) / 365.0 AS years_between,
           ROUND((new_salary - old_salary) * 100.0 / NULLIF(old_salary, 0), 2) AS salary_jump_pct
    FROM promo
    WHERE prev_promo_date IS NOT NULL
)
SELECT e.job_level,
       COUNT(*)                                                     AS promo_events,
       COUNT(DISTINCT g.emp_id)                                     AS promoted_emp,
       ROUND(AVG(g.years_between), 2)                               AS avg_years_between,
       ROUND(MIN(g.years_between), 2)                               AS fastest_years,
       ROUND(AVG(g.salary_jump_pct), 2)                             AS avg_salary_jump_pct,
       ROUND(AVG(g.years_between) - AVG(AVG(g.years_between)) OVER (), 2) AS vs_overall_gap,
       CASE WHEN AVG(g.years_between) < 1.5 THEN 'fast_track'
            WHEN AVG(g.years_between) > 4 THEN 'slow_track'
            ELSE 'normal' END AS promo_pace
FROM gap g
JOIN employees e ON g.emp_id = e.emp_id
GROUP BY e.job_level
ORDER BY e.job_level;

-- ------------------------------------------------------------------------------
-- [275] 流失·原因归因 | 人力 | 离职原因分布与可归因风险因素交叉分析
-- ------------------------------------------------------------------------------
WITH lv AS (
    SELECT e.emp_id, e.dept_id, e.job_level, e.position,
           (COALESCE(e.leave_date, TRUNC(SYSDATE)) - e.hire_date) / 365.0 AS tenure_at_exit,
           c.change_type,
           p.avg_score,
           a.abnormal_rate
    FROM employees e
    LEFT JOIN (SELECT emp_id, AVG(score) AS avg_score FROM performance GROUP BY emp_id) p
           ON e.emp_id = p.emp_id
    LEFT JOIN (SELECT emp_id,
                      ROUND(SUM(CASE WHEN status <> 'normal' THEN 1 ELSE 0 END) * 100.0
                            / NULLIF(COUNT(*), 0), 2) AS abnormal_rate
               FROM attendance GROUP BY emp_id) a ON e.emp_id = a.emp_id
    LEFT JOIN (SELECT emp_id, change_type,
                      ROW_NUMBER() OVER (PARTITION BY emp_id ORDER BY change_date DESC) rn
               FROM emp_changes) c ON e.emp_id = c.emp_id AND c.rn = 1
    WHERE e.status = 'left'
)
SELECT d.dept_name,
       COALESCE(l.change_type, 'no_record')                          AS last_change_type,
       COUNT(*)                                                      AS left_cnt,
       ROUND(AVG(l.tenure_at_exit), 2)                               AS avg_tenure_at_exit,
       ROUND(AVG(l.avg_score), 2)                                    AS avg_perf_before_exit,
       ROUND(AVG(COALESCE(l.abnormal_rate, 0)), 2)                   AS avg_abnormal_rate,
       SUM(CASE WHEN l.tenure_at_exit < 1 THEN 1 ELSE 0 END)         AS early_exit_cnt,
       ROUND(SUM(CASE WHEN l.tenure_at_exit < 1 THEN 1 ELSE 0 END) * 100.0
             / NULLIF(COUNT(*), 0), 2)                               AS early_exit_pct,
       ROUND(COUNT(*) * 100.0 / NULLIF(SUM(COUNT(*)) OVER (), 0), 2) AS share_pct,
       RANK() OVER (ORDER BY COUNT(*) DESC)                          AS reason_rank
FROM lv l
JOIN departments d ON l.dept_id = d.dept_id
GROUP BY d.dept_name, COALESCE(l.change_type, 'no_record')
ORDER BY left_cnt DESC;

-- ------------------------------------------------------------------------------
-- [276] 多元·包容分析 | 人力 | 性别与年龄结构的多元化分布及均衡度
-- ------------------------------------------------------------------------------
WITH demo AS (
    SELECT e.dept_id,
           e.gender, e.birth_date,
           CASE WHEN (EXTRACT(YEAR FROM TRUNC(SYSDATE)) - EXTRACT(YEAR FROM e.birth_date)) < 26 THEN 'gen_z'
                WHEN (EXTRACT(YEAR FROM TRUNC(SYSDATE)) - EXTRACT(YEAR FROM e.birth_date)) < 36 THEN 'millennial'
                WHEN (EXTRACT(YEAR FROM TRUNC(SYSDATE)) - EXTRACT(YEAR FROM e.birth_date)) < 46 THEN 'gen_x'
                ELSE 'boomer' END AS age_band,
           e.job_level, e.salary
    FROM employees e
    WHERE e.status = 'active'
)
SELECT d.dept_name,
       COUNT(*)                                                        AS headcount,
       SUM(CASE WHEN a.gender = 'F' THEN 1 ELSE 0 END)                 AS female_cnt,
       ROUND(SUM(CASE WHEN a.gender = 'F' THEN 1 ELSE 0 END) * 100.0
             / NULLIF(COUNT(*), 0), 2)                                 AS female_pct,
       SUM(CASE WHEN a.gender = 'F' AND a.job_level >= 4 THEN 1 ELSE 0 END) AS female_leader_cnt,
       ROUND(SUM(CASE WHEN a.gender = 'F' AND a.job_level >= 4 THEN 1 ELSE 0 END) * 100.0
             / NULLIF(SUM(CASE WHEN a.job_level >= 4 THEN 1 ELSE 0 END), 0), 2) AS female_leader_pct,
       ROUND(AVG(CASE WHEN a.gender = 'F' THEN a.salary END), 2)       AS avg_female_salary,
       ROUND(AVG(CASE WHEN a.gender = 'M' THEN a.salary END), 2)       AS avg_male_salary,
       ROUND(AVG(CASE WHEN a.gender = 'F' THEN a.salary END)
             / NULLIF(AVG(CASE WHEN a.gender = 'M' THEN a.salary END), 0), 3) AS gender_pay_ratio,
       ROUND(AVG((EXTRACT(YEAR FROM TRUNC(SYSDATE)) - EXTRACT(YEAR FROM a.birth_date))), 1)             AS avg_age,
       CASE WHEN SUM(CASE WHEN a.gender = 'F' THEN 1 ELSE 0 END) * 1.0 / NULLIF(COUNT(*), 0)
                 BETWEEN 0.40 AND 0.60 THEN 'balanced'
            ELSE 'imbalanced' END AS gender_balance_flag
FROM demo a
JOIN departments d ON a.dept_id = d.dept_id
GROUP BY d.dept_name
ORDER BY female_pct;

-- ------------------------------------------------------------------------------
-- [277] 敬业·代理指标 | 人力 | 员工敬业度代理指标与团队氛围评估
-- ------------------------------------------------------------------------------
WITH sig AS (
    SELECT e.emp_id, e.dept_id,
           COALESCE(a.attendance_rate, 0)                            AS attendance_rate,
           COALESCE(a.avg_overtime, 0)                               AS avg_overtime,
           COALESCE(t.train_cnt, 0)                                  AS train_cnt,
           COALESCE(lv.leave_days, 0)                                AS leave_days,
           COALESCE(p.avg_score, 0)                                  AS perf_score
    FROM employees e
    LEFT JOIN (SELECT emp_id,
                      ROUND(SUM(CASE WHEN status = 'normal' THEN 1 ELSE 0 END) * 100.0
                            / NULLIF(COUNT(*), 0), 2) AS attendance_rate,
                      ROUND(AVG(overtime_hours), 2) AS avg_overtime
               FROM attendance
               WHERE att_date >= (TRUNC(SYSDATE) - 180)
               GROUP BY emp_id) a ON e.emp_id = a.emp_id
    LEFT JOIN (SELECT emp_id, COUNT(*) AS train_cnt FROM training_records
               WHERE status = 'completed' GROUP BY emp_id) t ON e.emp_id = t.emp_id
    LEFT JOIN (SELECT emp_id, SUM(days) AS leave_days FROM leaves
               WHERE start_date >= (TRUNC(SYSDATE) - 365)
               GROUP BY emp_id) lv ON e.emp_id = lv.emp_id
    LEFT JOIN (SELECT emp_id, AVG(score) AS avg_score FROM performance
               GROUP BY emp_id) p ON e.emp_id = p.emp_id
    WHERE e.status = 'active'
)
SELECT d.dept_name,
       COUNT(*)                                                             AS emp_cnt,
       ROUND(AVG(s.attendance_rate), 2)                                     AS avg_attendance,
       ROUND(AVG(s.avg_overtime), 2)                                        AS avg_overtime,
       ROUND(AVG(s.train_cnt), 2)                                           AS avg_training,
       ROUND(AVG(s.leave_days), 1)                                          AS avg_leave_days,
       ROUND((AVG(s.attendance_rate) * 0.3 + AVG(s.perf_score) * 0.4
              + LEAST(AVG(s.train_cnt) * 10.0, 100.0) * 0.2
              + LEAST(AVG(s.leave_days) * 2.0, 100.0) * 0.1), 2)          AS engagement_index,
       RANK() OVER (ORDER BY (AVG(s.attendance_rate) * 0.3 + AVG(s.perf_score) * 0.4
                              + LEAST(AVG(s.train_cnt) * 10.0, 100.0) * 0.2
                              + LEAST(AVG(s.leave_days) * 2.0, 100.0) * 0.1) DESC) AS engagement_rank
FROM sig s
JOIN departments d ON s.dept_id = d.dept_id
GROUP BY d.dept_name
ORDER BY engagement_index DESC;

-- ------------------------------------------------------------------------------
-- [278] 预算·人力执行 | 人力 | 人力成本预算与实际发放的执行对比
-- ------------------------------------------------------------------------------
WITH act AS (
    SELECT e.dept_id,
           TO_CHAR(p.pay_date, 'YYYY-MM')                                   AS pay_ym,
           SUM(p.gross_pay)                                             AS actual_cost,
           COUNT(DISTINCT p.emp_id)                                     AS paid_headcount
    FROM payroll p
    JOIN employees e ON p.emp_id = e.emp_id
    WHERE p.pay_date >= (TRUNC(SYSDATE) - 365)
    GROUP BY e.dept_id, TO_CHAR(p.pay_date, 'YYYY-MM')
),
bud AS (
    SELECT dept_id, SUM(budget_cost) AS yearly_budget
    FROM departments
    GROUP BY dept_id
)
SELECT d.dept_name, a.pay_ym,
       ROUND(a.actual_cost, 2)                                        AS actual_cost,
       a.paid_headcount,
       ROUND(b.yearly_budget / 12.0, 2)                               AS monthly_budget,
       ROUND(a.actual_cost - b.yearly_budget / 12.0, 2)               AS variance,
       ROUND((a.actual_cost - b.yearly_budget / 12.0) * 100.0
             / NULLIF(b.yearly_budget / 12.0, 0), 2)                  AS variance_pct,
       SUM(a.actual_cost) OVER (PARTITION BY a.dept_id ORDER BY a.pay_ym
                                ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS ytd_cost,
       ROUND(AVG(a.actual_cost) OVER (PARTITION BY a.dept_id ORDER BY a.pay_ym
                                      ROWS BETWEEN 2 PRECEDING AND CURRENT ROW), 2) AS ma3_cost,
       CASE WHEN a.actual_cost > b.yearly_budget / 12.0 * 1.15 THEN 'over_budget'
            WHEN a.actual_cost < b.yearly_budget / 12.0 * 0.85 THEN 'under_budget'
            ELSE 'on_budget' END AS budget_flag
FROM act a
JOIN departments d ON a.dept_id = d.dept_id
LEFT JOIN bud b ON a.dept_id = b.dept_id
ORDER BY d.dept_name, a.pay_ym;

-- ------------------------------------------------------------------------------
-- [279] 趋势·成本同比 | 人力 | 人力成本年度同比与结构变化分解
-- ------------------------------------------------------------------------------
WITH y AS (
    SELECT EXTRACT(YEAR FROM p.pay_date)                                     AS yr,
           e.dept_id,
           SUM(p.gross_pay)                                            AS gross,
           SUM(p.tax)                                                  AS tax,
           SUM(p.social_insurance)                                     AS social,
           SUM(p.net_pay)                                              AS net
    FROM payroll p
    JOIN employees e ON p.emp_id = e.emp_id
    WHERE p.pay_date >= (TRUNC(SYSDATE) - 1095)
    GROUP BY EXTRACT(YEAR FROM p.pay_date), e.dept_id
)
SELECT d.dept_name, y.yr,
       ROUND(y.gross, 2)                                               AS gross_pay,
       ROUND(y.gross - LAG(y.gross) OVER (PARTITION BY y.dept_id ORDER BY y.yr), 2) AS yoy_delta,
       ROUND((y.gross - LAG(y.gross) OVER (PARTITION BY y.dept_id ORDER BY y.yr)) * 100.0
             / NULLIF(LAG(y.gross) OVER (PARTITION BY y.dept_id ORDER BY y.yr), 0), 2) AS yoy_pct,
       ROUND(y.tax * 100.0 / NULLIF(y.gross, 0), 2)                    AS tax_rate_pct,
       ROUND(y.social * 100.0 / NULLIF(y.gross, 0), 2)                 AS social_rate_pct,
       ROUND(y.net * 100.0 / NULLIF(y.gross, 0), 2)                    AS net_ratio_pct,
       ROUND(y.gross * 100.0 / NULLIF(SUM(y.gross) OVER (PARTITION BY y.yr), 0), 2) AS dept_share_pct,
       ROUND(y.gross - AVG(y.gross) OVER (PARTITION BY y.dept_id), 2)  AS vs_dept_avg
FROM y
JOIN departments d ON y.dept_id = d.dept_id
ORDER BY d.dept_name, y.yr;

-- ------------------------------------------------------------------------------
-- [280] 效率·加班成本 | 人力 | 加班投入与产出效率的成本效益分析
-- ------------------------------------------------------------------------------
WITH ot AS (
    SELECT e.dept_id, e.emp_id,
           SUM(a.overtime_hours)                                       AS ot_hours,
           COUNT(*)                                                    AS work_days,
           SUM(CASE WHEN a.status <> 'normal' THEN 1 ELSE 0 END)       AS abnormal_days
    FROM attendance a
    JOIN employees e ON a.emp_id = e.emp_id
    WHERE a.att_date >= (TRUNC(SYSDATE) - 180)
    GROUP BY e.dept_id, e.emp_id
),
perf AS (
    SELECT emp_id, AVG(score) AS avg_score FROM performance GROUP BY emp_id
)
SELECT d.dept_name,
       COUNT(*)                                                        AS emp_cnt,
       ROUND(SUM(o.ot_hours), 1)                                       AS total_ot_hours,
       ROUND(AVG(o.ot_hours), 1)                                       AS avg_ot_hours,
       ROUND(AVG(o.ot_hours) / NULLIF(AVG(o.work_days), 0), 2)         AS ot_per_workday,
       ROUND(AVG(p.avg_score), 2)                                      AS avg_perf_score,
       ROUND(((COUNT(*) * SUM((o.ot_hours) * (p.avg_score)) - SUM(o.ot_hours) * SUM(p.avg_score)) / NULLIF(SQRT((COUNT(*) * SUM((o.ot_hours) * (o.ot_hours)) - SUM(o.ot_hours) * SUM(o.ot_hours)) * (COUNT(*) * SUM((p.avg_score) * (p.avg_score)) - SUM(p.avg_score) * SUM(p.avg_score))), 0)), 4)                       AS corr_ot_perf,
       ROUND(AVG(o.abnormal_days) * 100.0 / NULLIF(AVG(o.work_days), 0), 2) AS abnormal_rate_pct,
       ROUND(SUM(o.ot_hours) * 1.0 / NULLIF(SUM(SUM(o.ot_hours)) OVER (), 0) * 100, 2) AS ot_share_pct,
       CASE WHEN AVG(o.ot_hours) / NULLIF(AVG(o.work_days), 0) > 2.0 THEN 'overtime_heavy'
            WHEN AVG(o.ot_hours) / NULLIF(AVG(o.work_days), 0) > 1.0 THEN 'moderate'
            ELSE 'healthy' END AS ot_intensity
FROM ot o
JOIN departments d ON o.dept_id = d.dept_id
LEFT JOIN perf p ON o.emp_id = p.emp_id
GROUP BY d.dept_name
ORDER BY avg_ot_hours DESC;

-- ------------------------------------------------------------------------------
-- [281] 组织·层级效能 | 人力 | 组织层级深度与管理成本效能分析
-- ------------------------------------------------------------------------------
WITH span AS (
    SELECT e.manager_id,
           COUNT(*)                                       AS direct_reports,
           COUNT(DISTINCT e.dept_id)                      AS dept_spread,
           ROUND(AVG(e.salary), 2)                        AS avg_report_salary,
           COUNT(DISTINCT e.job_level)                    AS level_spread
    FROM employees e
    WHERE e.status = 'active' AND e.manager_id IS NOT NULL
    GROUP BY e.manager_id
),
chain AS (
    SELECT e.emp_id, e.manager_id, e.job_level,
           (SELECT COUNT(*) FROM employees s WHERE s.manager_id = e.emp_id AND s.status = 'active') AS reports
    FROM employees e
    WHERE e.status = 'active'
)
SELECT d.dept_name,
       COUNT(*)                                                          AS headcount,
       SUM(CASE WHEN c.reports > 0 THEN 1 ELSE 0 END)                    AS manager_cnt,
       ROUND(SUM(CASE WHEN c.reports > 0 THEN 1 ELSE 0 END) * 100.0
             / NULLIF(COUNT(*), 0), 2)                                   AS mgr_ratio_pct,
       ROUND(AVG(s.direct_reports), 2)                                   AS avg_span,
       ROUND(MAX(s.direct_reports), 0)                                   AS max_span,
       ROUND(STDDEV_SAMP(s.direct_reports), 2)                             AS span_stddev,
       ROUND(AVG(s.dept_spread), 2)                                      AS avg_dept_spread,
       CASE WHEN SUM(CASE WHEN c.reports > 0 THEN 1 ELSE 0 END) * 1.0 / NULLIF(COUNT(*), 0) > 0.40
                 THEN 'too_many_managers'
            WHEN AVG(s.direct_reports) > 12 THEN 'span_too_wide'
            WHEN AVG(s.direct_reports) < 3 THEN 'span_too_narrow'
            ELSE 'balanced' END AS org_shape_flag,
       RANK() OVER (ORDER BY SUM(CASE WHEN c.reports > 0 THEN 1 ELSE 0 END) * 1.0
                             / NULLIF(COUNT(*), 0))                      AS flatness_rank
FROM chain c
JOIN employees e ON c.emp_id = e.emp_id
JOIN departments d ON e.dept_id = d.dept_id
LEFT JOIN span s ON c.emp_id = s.manager_id
GROUP BY d.dept_name
ORDER BY mgr_ratio_pct;

-- ------------------------------------------------------------------------------
-- [282] 价值·员工回报 | 人力 | 员工生命周期价值与人力投入回报评估
-- ------------------------------------------------------------------------------
WITH emp_val AS (
    SELECT e.emp_id, e.dept_id, e.job_level, e.salary, e.hire_date,
           COALESCE(p.avg_score, 0)                                  AS perf_score,
           COALESCE(py.total_gross, 0)                               AS total_paid,
           COALESCE(tr.total_train_cost, 0)                          AS train_cost,
           (TRUNC(SYSDATE) - e.hire_date) / 365.0                AS tenure_years
    FROM employees e
    LEFT JOIN (SELECT emp_id, AVG(score) AS avg_score FROM performance GROUP BY emp_id) p
           ON e.emp_id = p.emp_id
    LEFT JOIN (SELECT emp_id, SUM(gross_pay) AS total_gross FROM payroll
               WHERE pay_month >= TO_CHAR((TRUNC(SYSDATE) - 1095), 'YYYY-MM')
               GROUP BY emp_id) py ON e.emp_id = py.emp_id
    LEFT JOIN (SELECT emp_id, SUM(COALESCE(cost, 0)) AS total_train_cost
               FROM training_records GROUP BY emp_id) tr ON e.emp_id = tr.emp_id
    WHERE e.status = 'active'
)
SELECT d.dept_name,
       COUNT(*)                                                          AS emp_cnt,
       ROUND(AVG(v.tenure_years), 2)                                     AS avg_tenure,
       ROUND(AVG(v.perf_score), 2)                                       AS avg_perf,
       ROUND(SUM(v.total_paid + v.train_cost), 2)                        AS total_investment,
       ROUND(AVG(v.total_paid + v.train_cost), 2)                        AS avg_investment,
       ROUND(AVG((v.total_paid + v.train_cost) / NULLIF(v.tenure_years, 0)), 2) AS annual_investment,
       ROUND(AVG(v.perf_score) * 1000.0
             / NULLIF(AVG((v.total_paid + v.train_cost) / NULLIF(v.tenure_years, 0)), 0), 2) AS roi_index,
       ROUND(AVG(v.perf_score) * 1000.0
             / NULLIF(AVG((v.total_paid + v.train_cost) / NULLIF(v.tenure_years, 0)), 0)
             - AVG(AVG(v.perf_score) * 1000.0
                   / NULLIF(AVG((v.total_paid + v.train_cost) / NULLIF(v.tenure_years, 0)), 0)) OVER (), 2) AS roi_vs_avg,
       NTILE(5) OVER (ORDER BY AVG(v.perf_score) * 1000.0
                               / NULLIF(AVG((v.total_paid + v.train_cost) / NULLIF(v.tenure_years, 0)), 0) DESC) AS roi_tier
FROM emp_val v
JOIN departments d ON v.dept_id = d.dept_id
GROUP BY d.dept_name
ORDER BY roi_index DESC;

-- ------------------------------------------------------------------------------
-- [283] 风险·岗位空缺 | 人力 | 关键岗位空缺风险与业务连续性评估
-- ------------------------------------------------------------------------------
WITH pos AS (
    SELECT e.dept_id, e.position, e.job_level,
           COUNT(*)                                                   AS holder_cnt,
           ROUND(AVG(e.salary), 2)                                     AS avg_salary,
           SUM(CASE WHEN e.leave_date IS NOT NULL
                     AND e.leave_date >= (TRUNC(SYSDATE) - 180) THEN 1 ELSE 0 END) AS recent_leavers
    FROM employees e
    GROUP BY e.dept_id, e.position, e.job_level
),
open_req AS (
    SELECT dept_id, position, SUM(headcount) AS open_cnt
    FROM recruitment
    WHERE status = 'open'
    GROUP BY dept_id, position
)
SELECT d.dept_name, p.position, p.job_level,
       p.holder_cnt, p.recent_leavers,
       COALESCE(r.open_cnt, 0)                                          AS open_positions,
       ROUND(p.avg_salary, 2)                                           AS avg_salary,
       ROUND((COALESCE(r.open_cnt, 0) + p.recent_leavers) * 100.0
             / NULLIF(p.holder_cnt + COALESCE(r.open_cnt, 0) + p.recent_leavers, 0), 2) AS vacancy_impact_pct,
       ROUND(COALESCE(r.open_cnt, 0) * p.avg_salary / 12.0, 2)          AS monthly_gap_cost,
       CASE WHEN p.holder_cnt = 1 AND p.job_level >= 4 THEN 'single_point_failure'
            WHEN COALESCE(r.open_cnt, 0) >= p.holder_cnt THEN 'severely_understaffed'
            WHEN COALESCE(r.open_cnt, 0) > 0 AND p.job_level >= 4 THEN 'key_gap'
            WHEN COALESCE(r.open_cnt, 0) > 0 THEN 'normal_gap'
            ELSE 'fully_staffed' END AS vacancy_risk,
       DENSE_RANK() OVER (ORDER BY (COALESCE(r.open_cnt, 0) + p.recent_leavers) * 1.0
                                   / NULLIF(p.holder_cnt + COALESCE(r.open_cnt, 0) + p.recent_leavers, 0) DESC) AS risk_rank
FROM pos p
JOIN departments d ON p.dept_id = d.dept_id
LEFT JOIN open_req r ON p.dept_id = r.dept_id AND p.position = r.position
WHERE p.job_level >= 3
ORDER BY vacancy_impact_pct DESC
FETCH FIRST 150 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [284] 预警·人力风险 | 人力 | 人力综合风险预警（流失+负荷+成本+绩效）
-- ------------------------------------------------------------------------------
WITH risk AS (
    SELECT e.emp_id, e.dept_id, e.emp_name, e.job_level, e.position,
           COALESCE(p.avg_score, 0)                                     AS perf_score,
           COALESCE(a.abnormal_rate, 0)                                 AS abnormal_rate,
           COALESCE(a.avg_ot, 0)                                        AS avg_ot,
           COALESCE(lv.leave_days, 0)                                   AS leave_days,
           COALESCE(s.pay_ratio, 0)                                     AS pay_ratio,
           (TRUNC(SYSDATE) - e.hire_date) / 365.0                   AS tenure_years
    FROM employees e
    LEFT JOIN (SELECT emp_id, AVG(score) AS avg_score FROM performance GROUP BY emp_id) p
           ON e.emp_id = p.emp_id
    LEFT JOIN (SELECT emp_id,
                      ROUND(SUM(CASE WHEN status <> 'normal' THEN 1 ELSE 0 END) * 100.0
                            / NULLIF(COUNT(*), 0), 2) AS abnormal_rate,
                      ROUND(AVG(overtime_hours), 2) AS avg_ot
               FROM attendance
               WHERE att_date >= (TRUNC(SYSDATE) - 180)
               GROUP BY emp_id) a ON e.emp_id = a.emp_id
    LEFT JOIN (SELECT emp_id, SUM(days) AS leave_days FROM leaves
               WHERE start_date >= (TRUNC(SYSDATE) - 365) GROUP BY emp_id) lv
           ON e.emp_id = lv.emp_id
    LEFT JOIN (SELECT sb.emp_id,
                      ROUND(sb.emp_avg_base / NULLIF(cb.company_avg_base, 0), 3) AS pay_ratio
               FROM (SELECT emp_id, AVG(base_salary) AS emp_avg_base
                     FROM salaries GROUP BY emp_id) sb
               CROSS JOIN (SELECT AVG(base_salary) AS company_avg_base
                           FROM salaries) cb) s ON e.emp_id = s.emp_id
    WHERE e.status = 'active'
),
scored AS (
    SELECT r.*,
           (CASE WHEN r.perf_score < 65 THEN 30 ELSE 0 END
            + CASE WHEN r.abnormal_rate > 20 THEN 20 ELSE 0 END
            + CASE WHEN r.avg_ot > 3 THEN 15 ELSE 0 END
            + CASE WHEN r.leave_days > 20 THEN 10 ELSE 0 END
            + CASE WHEN r.pay_ratio < 0.85 THEN 25 ELSE 0 END
            + CASE WHEN r.tenure_years BETWEEN 1 AND 2 THEN 10 ELSE 0 END) AS risk_score
    FROM risk r
)
SELECT d.dept_name, s.emp_name, s.position, s.job_level,
       s.perf_score, s.abnormal_rate, s.avg_ot, s.leave_days, s.pay_ratio,
       s.risk_score,
       CASE WHEN s.risk_score >= 60 THEN 'critical'
            WHEN s.risk_score >= 40 THEN 'high'
            WHEN s.risk_score >= 20 THEN 'medium'
            ELSE 'low' END AS risk_level,
       ROW_NUMBER() OVER (PARTITION BY s.dept_id ORDER BY s.risk_score DESC) AS dept_risk_rank,
       ROUND(s.risk_score - AVG(s.risk_score) OVER (), 1)                    AS score_vs_avg
FROM scored s
JOIN departments d ON s.dept_id = d.dept_id
WHERE s.risk_score >= 20
ORDER BY s.risk_score DESC
FETCH FIRST 200 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [285] 看板·部门健康 | 人力 | 部门人力健康度综合看板
-- ------------------------------------------------------------------------------
WITH hc AS (
    SELECT e.dept_id,
           COUNT(*)                                                     AS headcount,
           SUM(CASE WHEN e.status = 'active' THEN 1 ELSE 0 END)          AS active_cnt,
           ROUND(AVG(e.salary), 2)                                       AS avg_salary,
           ROUND(AVG((TRUNC(SYSDATE) - e.hire_date) / 365.0), 2)     AS avg_tenure,
           COUNT(DISTINCT e.job_level)                                   AS level_layers
    FROM employees e
    GROUP BY e.dept_id
),
turn AS (
    SELECT e.dept_id,
           SUM(CASE WHEN e.leave_date >= (TRUNC(SYSDATE) - 365) THEN 1 ELSE 0 END) AS left_1y
    FROM employees e
    GROUP BY e.dept_id
),
pf AS (
    SELECT e.dept_id, ROUND(AVG(p.score), 2) AS avg_perf
    FROM performance p
    JOIN employees e ON p.emp_id = e.emp_id
    GROUP BY e.dept_id
),
at AS (
    SELECT e.dept_id,
           ROUND(SUM(CASE WHEN a.status = 'normal' THEN 1 ELSE 0 END) * 100.0
                 / NULLIF(COUNT(*), 0), 2) AS attendance_rate
    FROM attendance a
    JOIN employees e ON a.emp_id = e.emp_id
    WHERE a.att_date >= (TRUNC(SYSDATE) - 90)
    GROUP BY e.dept_id
),
cost AS (
    SELECT e.dept_id, SUM(py.gross_pay) AS payroll_cost
    FROM payroll py
    JOIN employees e ON py.emp_id = e.emp_id
    WHERE py.pay_month >= TO_CHAR((TRUNC(SYSDATE) - 365), 'YYYY-MM')
    GROUP BY e.dept_id
)
SELECT d.dept_name,
       h.headcount, h.active_cnt,
       COALESCE(t.left_1y, 0)                                             AS left_1y,
       ROUND(COALESCE(t.left_1y, 0) * 100.0 / NULLIF(h.active_cnt, 0), 2) AS turnover_pct,
       h.avg_salary, h.avg_tenure, h.level_layers,
       COALESCE(p.avg_perf, 0)                                            AS avg_perf,
       COALESCE(a.attendance_rate, 0)                                     AS attendance_rate,
       ROUND(c.payroll_cost, 2)                                           AS payroll_cost,
       ROUND(c.payroll_cost / NULLIF(h.active_cnt, 0), 2)                 AS cost_per_head,
       ROUND((COALESCE(p.avg_perf, 0) * 0.4 + COALESCE(a.attendance_rate, 0) * 0.3
              + (100 - COALESCE(t.left_1y, 0) * 100.0 / NULLIF(h.active_cnt, 0)) * 0.3), 2) AS health_score,
       RANK() OVER (ORDER BY (COALESCE(p.avg_perf, 0) * 0.4 + COALESCE(a.attendance_rate, 0) * 0.3
                              + (100 - COALESCE(t.left_1y, 0) * 100.0 / NULLIF(h.active_cnt, 0)) * 0.3) DESC) AS health_rank
FROM hc h
JOIN departments d ON h.dept_id = d.dept_id
LEFT JOIN turn t ON h.dept_id = t.dept_id
LEFT JOIN pf p ON h.dept_id = p.dept_id
LEFT JOIN at a ON h.dept_id = a.dept_id
LEFT JOIN cost c ON h.dept_id = c.dept_id
ORDER BY health_score DESC;

-- ------------------------------------------------------------------------------
-- [286] 质量·缺失检测 | 数据治理 | 核心业务表关键字段缺失与异常检测
-- ------------------------------------------------------------------------------
SELECT 'customers'                                                       AS table_name,
       COUNT(*)                                                          AS total_rows,
       SUM(CASE WHEN c.customer_name IS NULL THEN 1 ELSE 0 END)          AS null_name,
       SUM(CASE WHEN c.city IS NULL THEN 1 ELSE 0 END)                   AS null_city,
       SUM(CASE WHEN c.register_date IS NULL THEN 1 ELSE 0 END)          AS null_register_date,
       ROUND(SUM(CASE WHEN c.customer_name IS NULL THEN 1 ELSE 0 END) * 100.0
             / NULLIF(COUNT(*), 0), 2)                                   AS null_name_pct,
       SUM(CASE WHEN c.register_date > TRUNC(SYSDATE) THEN 1 ELSE 0 END)      AS future_date_rows,
       SUM(CASE WHEN (EXTRACT(YEAR FROM TRUNC(SYSDATE)) - EXTRACT(YEAR FROM c.register_date)) > 50 THEN 1 ELSE 0 END) AS implausible_rows
FROM customers c
UNION ALL
SELECT 'orders',
       COUNT(*),
       SUM(CASE WHEN o.customer_id IS NULL THEN 1 ELSE 0 END),
       SUM(CASE WHEN o.store_id IS NULL THEN 1 ELSE 0 END),
       SUM(CASE WHEN o.order_date IS NULL THEN 1 ELSE 0 END),
       ROUND(SUM(CASE WHEN o.customer_id IS NULL THEN 1 ELSE 0 END) * 100.0
             / NULLIF(COUNT(*), 0), 2),
       SUM(CASE WHEN o.order_date > TRUNC(SYSDATE) THEN 1 ELSE 0 END),
       SUM(CASE WHEN o.pay_amount < 0 THEN 1 ELSE 0 END)
FROM orders o
UNION ALL
SELECT 'employees',
       COUNT(*),
       SUM(CASE WHEN e.emp_name IS NULL THEN 1 ELSE 0 END),
       SUM(CASE WHEN e.dept_id IS NULL THEN 1 ELSE 0 END),
       SUM(CASE WHEN e.hire_date IS NULL THEN 1 ELSE 0 END),
       ROUND(SUM(CASE WHEN e.emp_name IS NULL THEN 1 ELSE 0 END) * 100.0
             / NULLIF(COUNT(*), 0), 2),
       SUM(CASE WHEN e.hire_date > TRUNC(SYSDATE) THEN 1 ELSE 0 END),
       SUM(CASE WHEN e.salary <= 0 THEN 1 ELSE 0 END)
FROM employees e
UNION ALL
SELECT 'transactions',
       COUNT(*),
       SUM(CASE WHEN t.account_id IS NULL THEN 1 ELSE 0 END),
       SUM(CASE WHEN t.txn_type IS NULL THEN 1 ELSE 0 END),
       SUM(CASE WHEN t.txn_date IS NULL THEN 1 ELSE 0 END),
       ROUND(SUM(CASE WHEN t.account_id IS NULL THEN 1 ELSE 0 END) * 100.0
             / NULLIF(COUNT(*), 0), 2),
       SUM(CASE WHEN t.txn_date > TRUNC(SYSDATE) THEN 1 ELSE 0 END),
       SUM(CASE WHEN t.amount = 0 THEN 1 ELSE 0 END)
FROM transactions t
ORDER BY 1;

-- ------------------------------------------------------------------------------
-- [287] 质量·重复识别 | 数据治理 | 重复记录识别与主数据合并建议
-- ------------------------------------------------------------------------------
WITH dup_cust AS (
    SELECT c.customer_name, c.city,
           COUNT(*)                                                      AS dup_cnt,
           COUNT(DISTINCT c.customer_id)                                 AS distinct_ids,
           MIN(c.customer_id)                                            AS keep_id,
           MAX(c.register_date)                                          AS latest_reg,
           LISTAGG(CAST(c.customer_id AS VARCHAR(50)), ',') WITHIN GROUP (ORDER BY c.customer_id)             AS id_list
    FROM customers c
    GROUP BY c.customer_name, c.city
    HAVING COUNT(*) > 1
),
dup_emp AS (
    SELECT e.emp_name, e.dept_id,
           COUNT(*)                                                      AS dup_cnt,
           MIN(e.emp_id)                                                 AS keep_id,
           LISTAGG(CAST(e.emp_id AS VARCHAR(50)), ',') WITHIN GROUP (ORDER BY e.emp_id)                       AS id_list
    FROM employees e
    GROUP BY e.emp_name, e.dept_id
    HAVING COUNT(*) > 1
)
SELECT 'customers'                                                       AS entity,
       d.customer_name                                                   AS entity_key,
       CAST(d.keep_id AS VARCHAR(50))                                              AS suggested_keep_id,
       d.dup_cnt, d.id_list,
       CASE WHEN d.dup_cnt > 3 THEN 'high_severity'
            WHEN d.dup_cnt = 2 THEN 'low_severity'
            ELSE 'medium_severity' END AS severity
FROM dup_cust d
UNION ALL
SELECT 'employees',
       de.emp_name,
       CAST(de.keep_id AS VARCHAR(50)),
       de.dup_cnt, de.id_list,
       CASE WHEN de.dup_cnt > 3 THEN 'high_severity'
            WHEN de.dup_cnt = 2 THEN 'low_severity'
            ELSE 'medium_severity' END
FROM dup_emp de
ORDER BY entity, dup_cnt DESC;

-- ------------------------------------------------------------------------------
-- [288] 质量·一致性 | 数据治理 | 跨表主数据一致性与引用完整性检查
-- ------------------------------------------------------------------------------
WITH order_cust AS (
    SELECT o.order_id, o.customer_id,
           CASE WHEN c.customer_id IS NULL THEN 1 ELSE 0 END AS orphan_flag
    FROM orders o
    LEFT JOIN customers c ON o.customer_id = c.customer_id
),
item_ord AS (
    SELECT oi.order_id,
           CASE WHEN o.order_id IS NULL THEN 1 ELSE 0 END AS orphan_flag
    FROM order_items oi
    LEFT JOIN orders o ON oi.order_id = o.order_id
),
txn_acct AS (
    SELECT t.txn_id,
           CASE WHEN a.account_id IS NULL THEN 1 ELSE 0 END AS orphan_flag
    FROM transactions t
    LEFT JOIN accounts a ON t.account_id = a.account_id
),
emp_dept AS (
    SELECT e.emp_id,
           CASE WHEN d.dept_id IS NULL THEN 1 ELSE 0 END AS orphan_flag
    FROM employees e
    LEFT JOIN departments d ON e.dept_id = d.dept_id
)
SELECT 'orders->customers'                                               AS relation,
       COUNT(*)                                                          AS total_rows,
       SUM(o.orphan_flag)                                                AS orphan_rows,
       ROUND(SUM(o.orphan_flag) * 100.0 / NULLIF(COUNT(*), 0), 4)        AS orphan_pct,
       CASE WHEN SUM(o.orphan_flag) * 1.0 / NULLIF(COUNT(*), 0) > 0.01 THEN 'fail'
            WHEN SUM(o.orphan_flag) > 0 THEN 'warn'
            ELSE 'pass' END AS integrity_status
FROM order_cust o
UNION ALL
SELECT 'order_items->orders', COUNT(*), SUM(i.orphan_flag),
       ROUND(SUM(i.orphan_flag) * 100.0 / NULLIF(COUNT(*), 0), 4),
       CASE WHEN SUM(i.orphan_flag) * 1.0 / NULLIF(COUNT(*), 0) > 0.01 THEN 'fail'
            WHEN SUM(i.orphan_flag) > 0 THEN 'warn' ELSE 'pass' END
FROM item_ord i
UNION ALL
SELECT 'transactions->accounts', COUNT(*), SUM(t.orphan_flag),
       ROUND(SUM(t.orphan_flag) * 100.0 / NULLIF(COUNT(*), 0), 4),
       CASE WHEN SUM(t.orphan_flag) * 1.0 / NULLIF(COUNT(*), 0) > 0.01 THEN 'fail'
            WHEN SUM(t.orphan_flag) > 0 THEN 'warn' ELSE 'pass' END
FROM txn_acct t
UNION ALL
SELECT 'employees->departments', COUNT(*), SUM(e.orphan_flag),
       ROUND(SUM(e.orphan_flag) * 100.0 / NULLIF(COUNT(*), 0), 4),
       CASE WHEN SUM(e.orphan_flag) * 1.0 / NULLIF(COUNT(*), 0) > 0.01 THEN 'fail'
            WHEN SUM(e.orphan_flag) > 0 THEN 'warn' ELSE 'pass' END
FROM emp_dept e
ORDER BY 4 DESC;

-- ------------------------------------------------------------------------------
-- [289] 对账·订单支付 | 数据治理 | 订单金额与支付流水差异对账
-- ------------------------------------------------------------------------------
WITH pay_agg AS (
    SELECT p.order_id,
           SUM(p.pay_amount)                                             AS paid_amount,
           COUNT(*)                                                      AS pay_cnt,
           MAX(p.pay_date)                                               AS last_pay_date,
           COUNT(DISTINCT p.pay_method)                                  AS method_cnt
    FROM payments p
    WHERE p.status = 'success'
    GROUP BY p.order_id
),
cmp AS (
    SELECT o.order_id, o.customer_id, o.store_id, o.order_date,
           o.pay_amount                                                  AS order_amount,
           COALESCE(a.paid_amount, 0)                                    AS paid_amount,
           COALESCE(a.pay_cnt, 0)                                        AS pay_cnt,
           ROUND(o.pay_amount - COALESCE(a.paid_amount, 0), 2)           AS diff_amount
    FROM orders o
    LEFT JOIN pay_agg a ON o.order_id = a.order_id
    WHERE o.status IN ('completed', 'shipped')
      AND o.order_date >= (TRUNC(SYSDATE) - 180)
)
SELECT TO_CHAR(c.order_date, 'YYYY-MM')                                      AS recon_ym,
       COUNT(*)                                                          AS order_cnt,
       SUM(CASE WHEN c.pay_cnt = 0 THEN 1 ELSE 0 END)                    AS missing_payment_cnt,
       SUM(CASE WHEN ABS(c.diff_amount) > 0.01 THEN 1 ELSE 0 END)        AS amount_mismatch_cnt,
       SUM(CASE WHEN c.paid_amount > c.order_amount THEN 1 ELSE 0 END)   AS overpaid_cnt,
       ROUND(SUM(c.order_amount), 2)                                     AS total_order_amount,
       ROUND(SUM(c.paid_amount), 2)                                      AS total_paid_amount,
       ROUND(SUM(c.diff_amount), 2)                                      AS net_diff,
       ROUND(SUM(CASE WHEN ABS(c.diff_amount) > 0.01 THEN 1 ELSE 0 END) * 100.0
             / NULLIF(COUNT(*), 0), 4)                                   AS mismatch_rate_pct,
       SUM(CASE WHEN ABS(c.diff_amount) > 0.01 THEN c.diff_amount ELSE 0 END) AS unreconciled_amount
FROM cmp c
GROUP BY TO_CHAR(c.order_date, 'YYYY-MM')
ORDER BY recon_ym DESC;

-- ------------------------------------------------------------------------------
-- [290] 跨域·客户价值 | 综合 | 电商消费与金融资产的客户综合价值分层
-- ------------------------------------------------------------------------------
WITH eco AS (
    SELECT o.customer_id,
           SUM(o.pay_amount)                                             AS gmv,
           COUNT(*)                                                      AS order_cnt,
           MAX(o.order_date)                                             AS last_order_date
    FROM orders o
    WHERE o.order_date >= (TRUNC(SYSDATE) - 365)
      AND o.status = 'completed'
    GROUP BY o.customer_id
),
fin AS (
    SELECT a.cust_id,
           SUM(a.balance)                                                AS total_balance,
           COUNT(*)                                                      AS acct_cnt,
           MAX(t.last_txn_date)                                          AS last_txn_date
    FROM accounts a
    LEFT JOIN (SELECT account_id, MAX(txn_date) AS last_txn_date
               FROM transactions GROUP BY account_id) t
           ON a.account_id = t.account_id
    GROUP BY a.cust_id
)
SELECT c.customer_name, c.city, c."LEVEL",
       COALESCE(e.gmv, 0)                                                AS annual_gmv,
       COALESCE(e.order_cnt, 0)                                          AS order_cnt,
       COALESCE(f.total_balance, 0)                                      AS total_balance,
       COALESCE(f.acct_cnt, 0)                                           AS acct_cnt,
       ROUND(COALESCE(e.gmv, 0) + COALESCE(f.total_balance, 0) * 0.1, 2) AS blended_value,
       NTILE(5) OVER (ORDER BY COALESCE(e.gmv, 0)
                               + COALESCE(f.total_balance, 0) * 0.1 DESC) AS value_tier,
       CASE WHEN COALESCE(e.gmv, 0) > 0 AND COALESCE(f.total_balance, 0) > 0 THEN 'cross_domain'
            WHEN COALESCE(e.gmv, 0) > 0 THEN 'ecommerce_only'
            WHEN COALESCE(f.total_balance, 0) > 0 THEN 'finance_only'
            ELSE 'dormant' END AS domain_coverage,
       ROUND(COALESCE(e.gmv, 0) * 100.0
             / NULLIF(SUM(COALESCE(e.gmv, 0)) OVER (), 0), 3)            AS gmv_share_pct,
       RANK() OVER (ORDER BY COALESCE(e.gmv, 0) + COALESCE(f.total_balance, 0) * 0.1 DESC) AS value_rank
FROM customers c
LEFT JOIN eco e ON c.customer_id = e.customer_id
LEFT JOIN fin f ON c.customer_id = f.cust_id
ORDER BY blended_value DESC
FETCH FIRST 300 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [291] 跨域·门店效能 | 综合 | 门店销售指标与人力配置效能联动分析
-- ------------------------------------------------------------------------------
WITH sales AS (
    SELECT o.store_id,
           SUM(o.pay_amount)                                             AS gmv,
           COUNT(*)                                                      AS order_cnt,
           COUNT(DISTINCT o.customer_id)                                 AS cust_cnt,
           ROUND(AVG(o.pay_amount), 2)                                   AS avg_order_value
    FROM orders o
    WHERE o.order_date >= (TRUNC(SYSDATE) - 365)
      AND o.status = 'completed'
    GROUP BY o.store_id
),
target AS (
    SELECT store_id, SUM(target_amt) AS target_amt
    FROM store_targets
    WHERE TO_CHAR(target_month, 'YYYY-MM') >= TO_CHAR((TRUNC(SYSDATE) - 365), 'YYYY-MM')
    GROUP BY store_id
)
SELECT s.store_name, s.city, s.region,
       COALESCE(sa.gmv, 0)                                               AS gmv,
       COALESCE(sa.order_cnt, 0)                                         AS order_cnt,
       COALESCE(sa.cust_cnt, 0)                                          AS cust_cnt,
       COALESCE(sa.avg_order_value, 0)                                   AS avg_order_value,
       COALESCE(t.target_amt, 0)                                         AS target_amt,
       ROUND(COALESCE(sa.gmv, 0) * 100.0 / NULLIF(t.target_amt, 0), 2)   AS target_achieve_pct,
       (SELECT COUNT(*) FROM employees e
        WHERE e.dept_id = s.dept_id AND e.status = 'active')             AS staff_cnt,
       ROUND(COALESCE(sa.gmv, 0)
             / NULLIF((SELECT COUNT(*) FROM employees e2
                       WHERE e2.dept_id = s.dept_id AND e2.status = 'active'), 0), 2) AS gmv_per_staff,
       ROUND(COALESCE(sa.order_cnt, 0)
             / NULLIF((SELECT COUNT(*) FROM employees e3
                       WHERE e3.dept_id = s.dept_id AND e3.status = 'active'), 0), 1) AS orders_per_staff,
       RANK() OVER (ORDER BY COALESCE(sa.gmv, 0) * 100.0
                            / NULLIF(t.target_amt, 0) DESC)              AS achieve_rank,
       CASE WHEN COALESCE(sa.gmv, 0) * 1.0 / NULLIF(t.target_amt, 0) >= 1.1 THEN 'over_achieved'
            WHEN COALESCE(sa.gmv, 0) * 1.0 / NULLIF(t.target_amt, 0) >= 0.9 THEN 'on_target'
            WHEN COALESCE(sa.gmv, 0) * 1.0 / NULLIF(t.target_amt, 0) >= 0.7 THEN 'below_target'
            ELSE 'severely_behind' END AS achieve_status
FROM stores s
LEFT JOIN sales sa ON s.store_id = sa.store_id
LEFT JOIN target t ON s.store_id = t.store_id
ORDER BY target_achieve_pct DESC;

-- ------------------------------------------------------------------------------
-- [292] 跨域·分支行对比 | 综合 | 分支行存贷规模、客户数与盈利综合排名
-- ------------------------------------------------------------------------------
WITH dep AS (
    SELECT a.branch_id, SUM(a.balance) AS deposit_balance, COUNT(DISTINCT a.cust_id) AS dep_cust
    FROM accounts a
    WHERE a.account_type IN ('savings', 'checking')
    GROUP BY a.branch_id
),
loan AS (
    SELECT l.branch_id,
           SUM(l.loan_amount)                                            AS loan_balance,
           SUM(CASE WHEN l.status = 'overdue' THEN l.loan_amount ELSE 0 END) AS overdue_balance,
           COUNT(*)                                                      AS loan_cnt
    FROM loans l
    GROUP BY l.branch_id
)
SELECT b.branch_name, b.city, b.region,
       COALESCE(d.deposit_balance, 0)                                    AS deposit_balance,
       COALESCE(l.loan_balance, 0)                                       AS loan_balance,
       COALESCE(d.dep_cust, 0)                                           AS deposit_cust,
       COALESCE(l.loan_cnt, 0)                                           AS loan_cnt,
       ROUND(COALESCE(l.loan_balance, 0) * 100.0
             / NULLIF(COALESCE(d.deposit_balance, 0), 0), 2)             AS loan_to_deposit_pct,
       ROUND(COALESCE(l.overdue_balance, 0) * 100.0
             / NULLIF(COALESCE(l.loan_balance, 0), 0), 2)                AS npl_ratio_pct,
       ROUND(COALESCE(d.deposit_balance, 0) / NULLIF(COALESCE(d.dep_cust, 0), 0), 2) AS avg_deposit,
       RANK() OVER (ORDER BY COALESCE(d.deposit_balance, 0) DESC)        AS deposit_rank,
       RANK() OVER (ORDER BY COALESCE(l.loan_balance, 0) DESC)           AS loan_rank,
       ROUND(COALESCE(d.deposit_balance, 0) * 100.0
             / NULLIF(SUM(COALESCE(d.deposit_balance, 0)) OVER (), 0), 2) AS deposit_share_pct,
       CASE WHEN COALESCE(l.overdue_balance, 0) * 1.0
                 / NULLIF(COALESCE(l.loan_balance, 0), 0) > 0.05 THEN 'asset_quality_alert'
            WHEN COALESCE(l.loan_balance, 0) * 1.0
                 / NULLIF(COALESCE(d.deposit_balance, 0), 0) > 0.85 THEN 'liquidity_pressure'
            ELSE 'healthy' END AS branch_flag
FROM branches b
LEFT JOIN dep d ON b.branch_id = d.branch_id
LEFT JOIN loan l ON b.branch_id = l.branch_id
ORDER BY deposit_balance DESC;

-- ------------------------------------------------------------------------------
-- [293] 下钻·多维分析 | 综合 | 城市×品类×月份的销售多维下钻汇总
-- ------------------------------------------------------------------------------
WITH base AS (
    SELECT s.city,
           c.category_name,
           TO_CHAR(o.order_date, 'YYYY-MM')                                  AS ym,
           SUM(oi.amount)                                                AS sales_amount,
           SUM(oi.quantity)                                              AS qty,
           COUNT(DISTINCT o.order_id)                                    AS order_cnt
    FROM orders o
    JOIN order_items oi ON o.order_id = oi.order_id
    JOIN products p ON oi.product_id = p.product_id
    JOIN categories c ON p.category_id = c.category_id
    JOIN stores s ON o.store_id = s.store_id
    WHERE o.status = 'completed'
      AND o.order_date >= (TRUNC(SYSDATE) - 365)
    GROUP BY s.city, c.category_name, TO_CHAR(o.order_date, 'YYYY-MM')
)
SELECT * FROM (
SELECT 'city'                                                            AS dim_level,
       CAST(b.city AS VARCHAR(100))                                                AS dim_value,
       NULL                                                              AS dim_value2,
       SUM(b.sales_amount)                                               AS sales_amount,
       SUM(b.qty)                                                        AS qty,
       SUM(b.order_cnt)                                                  AS order_cnt,
       ROUND(SUM(b.sales_amount) * 100.0
             / NULLIF(SUM(SUM(b.sales_amount)) OVER (), 0), 2)           AS share_pct,
       RANK() OVER (ORDER BY SUM(b.sales_amount) DESC)                   AS dim_rank
FROM base b
GROUP BY b.city
UNION ALL
SELECT 'category', NULL, CAST(b.category_name AS VARCHAR(100)),
       SUM(b.sales_amount), SUM(b.qty), SUM(b.order_cnt),
       ROUND(SUM(b.sales_amount) * 100.0
             / NULLIF(SUM(SUM(b.sales_amount)) OVER (), 0), 2),
       RANK() OVER (ORDER BY SUM(b.sales_amount) DESC)
FROM base b
GROUP BY b.category_name
UNION ALL
SELECT 'city_category', CAST(b.city AS VARCHAR(100)), CAST(b.category_name AS VARCHAR(100)),
       SUM(b.sales_amount), SUM(b.qty), SUM(b.order_cnt),
       ROUND(SUM(b.sales_amount) * 100.0
             / NULLIF(SUM(SUM(b.sales_amount)) OVER (), 0), 2),
       RANK() OVER (ORDER BY SUM(b.sales_amount) DESC)
FROM base b
GROUP BY b.city, b.category_name
) ORDER BY dim_level, sales_amount DESC;

-- ------------------------------------------------------------------------------
-- [294] 波动·异常检测 | 综合 | 核心指标时间序列的统计异常检测
-- ------------------------------------------------------------------------------
WITH daily AS (
    SELECT TRUNC(o.order_date)                                       AS dt,
           SUM(o.pay_amount)                                             AS gmv,
           COUNT(*)                                                      AS order_cnt
    FROM orders o
    WHERE o.order_date >= (TRUNC(SYSDATE) - 180)
      AND o.status = 'completed'
    GROUP BY TRUNC(o.order_date)
),
stat AS (
    SELECT d.dt, d.gmv, d.order_cnt,
           AVG(d.gmv) OVER (ORDER BY d.dt ROWS BETWEEN 29 PRECEDING AND 1 PRECEDING) AS ma30,
           STDDEV_SAMP(d.gmv) OVER (ORDER BY d.dt ROWS BETWEEN 29 PRECEDING AND 1 PRECEDING) AS sd30
    FROM daily d
)
SELECT s.dt,
       ROUND(s.gmv, 2)                                                   AS gmv,
       s.order_cnt,
       ROUND(s.ma30, 2)                                                  AS ma30,
       ROUND(s.sd30, 2)                                                  AS sd30,
       ROUND((s.gmv - s.ma30) / NULLIF(s.sd30, 0), 2)                    AS z_score,
       ROUND((s.gmv - s.ma30) * 100.0 / NULLIF(s.ma30, 0), 2)            AS deviation_pct,
       CASE WHEN s.sd30 IS NULL OR s.sd30 = 0 THEN 'insufficient_history'
            WHEN ABS((s.gmv - s.ma30) / NULLIF(s.sd30, 0)) > 3 THEN 'extreme_anomaly'
            WHEN ABS((s.gmv - s.ma30) / NULLIF(s.sd30, 0)) > 2 THEN 'anomaly'
            ELSE 'normal' END AS anomaly_flag,
       LAG(s.gmv) OVER (ORDER BY s.dt)                                   AS prev_gmv,
       ROUND((s.gmv - LAG(s.gmv) OVER (ORDER BY s.dt)) * 100.0
             / NULLIF(LAG(s.gmv) OVER (ORDER BY s.dt), 0), 2)            AS dod_pct
FROM stat s
ORDER BY s.dt DESC;

-- ------------------------------------------------------------------------------
-- [295] 对比·同比环比 | 综合 | 关键经营指标同比环比与复合增长率
-- ------------------------------------------------------------------------------
WITH m AS (
    SELECT TO_CHAR(o.order_date, 'YYYY-MM')                                  AS ym,
           SUM(o.pay_amount)                                             AS gmv,
           COUNT(*)                                                      AS order_cnt,
           COUNT(DISTINCT o.customer_id)                                 AS active_cust
    FROM orders o
    WHERE o.status = 'completed'
      AND o.order_date >= (TRUNC(SYSDATE) - 1095)
    GROUP BY TO_CHAR(o.order_date, 'YYYY-MM')
),
g AS (
    SELECT m.ym, m.gmv, m.order_cnt, m.active_cust,
           LAG(m.gmv) OVER (ORDER BY m.ym)                               AS prev_gmv,
           LAG(m.gmv, 12) OVER (ORDER BY m.ym)                           AS yoy_gmv,
           LAG(m.order_cnt) OVER (ORDER BY m.ym)                         AS prev_orders,
           LAG(m.active_cust) OVER (ORDER BY m.ym)                       AS prev_cust
    FROM m
)
SELECT g.ym,
       ROUND(g.gmv, 2)                                                   AS gmv,
       g.order_cnt, g.active_cust,
       ROUND((g.gmv - g.prev_gmv) * 100.0 / NULLIF(g.prev_gmv, 0), 2)    AS mom_pct,
       ROUND((g.gmv - g.yoy_gmv) * 100.0 / NULLIF(g.yoy_gmv, 0), 2)      AS yoy_pct,
       ROUND((g.order_cnt - g.prev_orders) * 100.0
             / NULLIF(g.prev_orders, 0), 2)                              AS order_mom_pct,
       ROUND(g.gmv / NULLIF(g.order_cnt, 0), 2)                          AS avg_order_value,
       ROUND(g.gmv / NULLIF(g.active_cust, 0), 2)                        AS gmv_per_cust,
       SUM(g.gmv) OVER (ORDER BY g.ym ROWS BETWEEN 11 PRECEDING AND CURRENT ROW) AS ttm_gmv,
       CASE WHEN (g.gmv - g.prev_gmv) * 1.0 / NULLIF(g.prev_gmv, 0) > 0.2 THEN 'surge'
            WHEN (g.gmv - g.prev_gmv) * 1.0 / NULLIF(g.prev_gmv, 0) < -0.2 THEN 'slump'
            ELSE 'stable' END AS trend_flag
FROM g
ORDER BY g.ym DESC;

-- ------------------------------------------------------------------------------
-- [296] 滚动·移动窗口 | 综合 | 滚动12月移动窗口指标与趋势平滑
-- ------------------------------------------------------------------------------
WITH m AS (
    SELECT TRUNC(o.order_date, 'MM')                                     AS ym,
           SUM(o.pay_amount)                                             AS gmv,
           COUNT(DISTINCT o.customer_id)                                 AS cust_cnt
    FROM orders o
    WHERE o.status = 'completed'
      AND o.order_date >= (TRUNC(SYSDATE) - 1095)
    GROUP BY TRUNC(o.order_date, 'MM')
),
w AS (
    SELECT m.ym, m.gmv, m.cust_cnt,
           SUM(m.gmv) OVER (ORDER BY m.ym ROWS BETWEEN 11 PRECEDING AND CURRENT ROW) AS rolling_12m,
           AVG(m.gmv) OVER (ORDER BY m.ym ROWS BETWEEN 2 PRECEDING AND CURRENT ROW)  AS ma3,
           AVG(m.gmv) OVER (ORDER BY m.ym ROWS BETWEEN 5 PRECEDING AND CURRENT ROW)  AS ma6,
           AVG(m.cust_cnt) OVER (ORDER BY m.ym ROWS BETWEEN 2 PRECEDING AND CURRENT ROW) AS ma3_cust,
           COUNT(*) OVER (ORDER BY m.ym ROWS BETWEEN 11 PRECEDING AND CURRENT ROW)   AS window_months
    FROM m
)
SELECT TO_CHAR(w.ym, 'YYYY-MM')                                              AS ym,
       ROUND(w.gmv, 2)                                                   AS gmv,
       w.cust_cnt,
       ROUND(w.rolling_12m, 2)                                           AS rolling_12m_gmv,
       ROUND(w.ma3, 2)                                                   AS ma3_gmv,
       ROUND(w.ma6, 2)                                                   AS ma6_gmv,
       ROUND(w.ma3_cust, 1)                                              AS ma3_cust,
       w.window_months,
       ROUND((w.gmv - w.ma6) * 100.0 / NULLIF(w.ma6, 0), 2)              AS vs_ma6_pct,
       ROUND(w.rolling_12m / NULLIF(w.cust_cnt, 0), 2)                   AS rolling_arpu,
       CASE WHEN w.gmv > w.ma6 * 1.2 THEN 'above_trend'
            WHEN w.gmv < w.ma6 * 0.8 THEN 'below_trend'
            ELSE 'in_trend' END AS trend_position
FROM w
ORDER BY w.ym DESC;

-- ------------------------------------------------------------------------------
-- [297] 帕累托·贡献度 | 综合 | 全业务帕累托分析与关键少数识别
-- ------------------------------------------------------------------------------
WITH prod AS (
    SELECT p.product_name, c.category_name,
           SUM(oi.amount)                                                AS sales_amount,
           SUM(oi.quantity)                                              AS qty
    FROM order_items oi
    JOIN products p ON oi.product_id = p.product_id
    JOIN categories c ON p.category_id = c.category_id
    JOIN orders o ON oi.order_id = o.order_id
    WHERE o.status = 'completed'
      AND o.order_date >= (TRUNC(SYSDATE) - 365)
    GROUP BY p.product_name, c.category_name
),
cum AS (
    SELECT pr.product_name, pr.category_name, pr.sales_amount, pr.qty,
           SUM(pr.sales_amount) OVER (ORDER BY pr.sales_amount DESC
                                      ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS cum_sales,
           SUM(pr.sales_amount) OVER ()                                  AS total_sales,
           ROW_NUMBER() OVER (ORDER BY pr.sales_amount DESC)             AS sku_rank,
           COUNT(*) OVER ()                                              AS total_sku
    FROM prod pr
)
SELECT c.product_name, c.category_name,
       ROUND(c.sales_amount, 2)                                          AS sales_amount,
       c.qty,
       c.sku_rank,
       ROUND(c.cum_sales, 2)                                             AS cum_sales,
       ROUND(c.cum_sales * 100.0 / NULLIF(c.total_sales, 0), 2)          AS cum_share_pct,
       ROUND(c.sku_rank * 100.0 / NULLIF(c.total_sku, 0), 2)             AS sku_share_pct,
       CASE WHEN c.cum_sales * 1.0 / NULLIF(c.total_sales, 0) <= 0.50 THEN 'A'
            WHEN c.cum_sales * 1.0 / NULLIF(c.total_sales, 0) <= 0.80 THEN 'B'
            WHEN c.cum_sales * 1.0 / NULLIF(c.total_sales, 0) <= 0.95 THEN 'C'
            ELSE 'D' END AS abc_class,
       ROUND(c.sales_amount * 100.0 / NULLIF(c.total_sales, 0), 3)       AS single_share_pct,
       CASE WHEN c.sku_rank * 1.0 / NULLIF(c.total_sku, 0) <= 0.2
                 AND c.cum_sales * 1.0 / NULLIF(c.total_sales, 0) <= 0.8 THEN 'vital_few'
            WHEN c.sku_rank * 1.0 / NULLIF(c.total_sku, 0) > 0.8 THEN 'trivial_many'
            ELSE 'middle' END AS pareto_zone
FROM cum c
ORDER BY c.sales_amount DESC
FETCH FIRST 300 ROWS ONLY;

-- ------------------------------------------------------------------------------
-- [298] 达成·目标看板 | 综合 | 目标达成率看板与差距归因分析
-- ------------------------------------------------------------------------------
WITH act AS (
    SELECT o.store_id,
           TO_CHAR(o.order_date, 'YYYY-MM')                                  AS ym,
           SUM(o.pay_amount)                                             AS gmv
    FROM orders o
    WHERE o.status = 'completed'
      AND o.order_date >= (TRUNC(SYSDATE) - 365)
    GROUP BY o.store_id, TO_CHAR(o.order_date, 'YYYY-MM')
),
cmp AS (
    SELECT t.store_id,
           TO_CHAR(t.target_month, 'YYYY-MM')                                AS ym,
           SUM(t.target_amt)                                             AS target_amt
    FROM store_targets t
    WHERE t.target_month >= (TRUNC(SYSDATE) - 365)
    GROUP BY t.store_id, TO_CHAR(t.target_month, 'YYYY-MM')
)
SELECT s.store_name, s.region, c.ym,
       COALESCE(c.target_amt, 0)                                         AS target_amt,
       COALESCE(a.gmv, 0)                                                AS actual_gmv,
       ROUND(COALESCE(a.gmv, 0) - COALESCE(c.target_amt, 0), 2)          AS gap_amount,
       ROUND(COALESCE(a.gmv, 0) * 100.0 / NULLIF(c.target_amt, 0), 2)    AS achieve_pct,
       ROUND(AVG(COALESCE(a.gmv, 0) * 100.0 / NULLIF(c.target_amt, 0))
             OVER (PARTITION BY c.store_id ORDER BY c.ym
                   ROWS BETWEEN 2 PRECEDING AND CURRENT ROW), 2)         AS ma3_achieve_pct,
       SUM(CASE WHEN COALESCE(a.gmv, 0) >= COALESCE(c.target_amt, 0) THEN 1 ELSE 0 END)
           OVER (PARTITION BY c.store_id)                                AS months_hit,
       COUNT(*) OVER (PARTITION BY c.store_id)                           AS months_total,
       ROUND(SUM(COALESCE(a.gmv, 0) - COALESCE(c.target_amt, 0))
             OVER (PARTITION BY c.store_id), 2)                          AS ytd_gap,
       CASE WHEN COALESCE(a.gmv, 0) * 1.0 / NULLIF(c.target_amt, 0) >= 1.0 THEN 'achieved'
            WHEN COALESCE(a.gmv, 0) * 1.0 / NULLIF(c.target_amt, 0) >= 0.9 THEN 'near_target'
            WHEN COALESCE(a.gmv, 0) * 1.0 / NULLIF(c.target_amt, 0) >= 0.7 THEN 'lagging'
            ELSE 'critical' END AS achieve_status,
       RANK() OVER (PARTITION BY c.ym
                    ORDER BY COALESCE(a.gmv, 0) * 1.0
                             / NULLIF(c.target_amt, 0) DESC)             AS month_rank
FROM cmp c
JOIN stores s ON c.store_id = s.store_id
LEFT JOIN act a ON c.store_id = a.store_id AND c.ym = a.ym
ORDER BY c.ym DESC, achieve_pct DESC;

-- ------------------------------------------------------------------------------
-- [299] 看板·KPI全景 | 综合 | 全业务域核心 KPI 全景看板
-- ------------------------------------------------------------------------------
WITH kpi AS (
    SELECT 'ecommerce_gmv'                                               AS kpi_name,
           ROUND(SUM(o.pay_amount), 2)                                   AS kpi_value,
           'CNY'                                                         AS unit
    FROM orders o
    WHERE o.status = 'completed'
      AND o.order_date >= (TRUNC(SYSDATE) - 30)
    UNION ALL
    SELECT 'ecommerce_orders', COUNT(*), 'cnt'
    FROM orders o
    WHERE o.status = 'completed' AND o.order_date >= (TRUNC(SYSDATE) - 30)
    UNION ALL
    SELECT 'ecommerce_active_cust', COUNT(DISTINCT o.customer_id), 'cnt'
    FROM orders o
    WHERE o.status = 'completed' AND o.order_date >= (TRUNC(SYSDATE) - 30)
    UNION ALL
    SELECT 'finance_deposit', ROUND(SUM(a.balance), 2), 'CNY'
    FROM accounts a
    UNION ALL
    SELECT 'finance_npl_ratio',
           ROUND(SUM(CASE WHEN l.status = 'overdue' THEN l.loan_amount ELSE 0 END) * 100.0
                 / NULLIF(SUM(l.loan_amount), 0), 2), 'pct'
    FROM loans l
    UNION ALL
    SELECT 'finance_txn_amount', ROUND(SUM(t.amount), 2), 'CNY'
    FROM transactions t
    WHERE t.txn_date >= (TRUNC(SYSDATE) - 30)
    UNION ALL
    SELECT 'hr_active_headcount', COUNT(*), 'cnt'
    FROM employees e WHERE e.status = 'active'
    UNION ALL
    SELECT 'hr_monthly_cost', ROUND(SUM(p.gross_pay), 2), 'CNY'
    FROM payroll p
    WHERE p.pay_month = TO_CHAR(TRUNC(SYSDATE), 'YYYY-MM')
    UNION ALL
    SELECT 'hr_avg_perf', ROUND(AVG(pf.score), 2), 'score'
    FROM performance pf
    UNION ALL
    SELECT 'inventory_stock_value',
           ROUND(SUM(i.stock_qty * p.price), 2), 'CNY'
    FROM inventory i
    JOIN products p ON i.product_id = p.product_id
)
SELECT k.kpi_name, k.kpi_value, k.unit,
       ROUND(k.kpi_value - AVG(k.kpi_value) OVER (), 2)                  AS vs_avg_gap,
       CASE WHEN k.kpi_value > AVG(k.kpi_value) OVER () THEN 'above_avg'
            ELSE 'below_avg' END AS vs_avg_flag
FROM kpi k
ORDER BY k.kpi_name;

-- ------------------------------------------------------------------------------
-- [300] 汇总·决策支持 | 综合 | 面向管理层的跨业务域决策支持汇总
-- ------------------------------------------------------------------------------
WITH ec AS (
    SELECT ROUND(SUM(o.pay_amount), 2)                                   AS gmv_30d,
           COUNT(*)                                                      AS orders_30d,
           COUNT(DISTINCT o.customer_id)                                 AS cust_30d,
           ROUND(SUM(o.discount_amount), 2)                              AS discount_30d
    FROM orders o
    WHERE o.status = 'completed' AND o.order_date >= TRUNC(SYSDATE) - 30
),
     fn AS (
         SELECT ROUND(SUM(a.balance), 2)                                      AS deposit_total,
                COUNT(DISTINCT a.cust_id)                                     AS fin_cust,
                ROUND(SUM(CASE WHEN l.status = 'overdue' THEN l.loan_amount ELSE 0 END) * 100.0
                          / NULLIF(SUM(l.loan_amount), 0), 2)                     AS npl_pct
         FROM accounts a
                  LEFT JOIN loans l ON a.cust_id = l.cust_id
     ),
     hr AS (
         SELECT SUM(CASE WHEN e.status = 'active' THEN 1 ELSE 0 END)          AS headcount,
                ROUND(AVG(CASE WHEN e.status = 'active' THEN e.salary END), 2) AS avg_salary,
                SUM(CASE WHEN e.leave_date >= TRUNC(SYSDATE) - 365 THEN 1 ELSE 0 END) AS left_1y
         FROM employees e
     ),
     pf AS (
         SELECT ROUND(AVG(score), 2) AS avg_perf FROM performance
     )
SELECT 'revenue'                                                         AS metric_group,
       'gmv_30d'                                                         AS metric_name,
       e.gmv_30d                                                         AS metric_value,
       ROUND(e.gmv_30d / NULLIF(e.orders_30d, 0), 2)                     AS derived_aov
FROM ec e
UNION ALL
SELECT 'revenue', 'orders_30d', e.orders_30d,
       ROUND(e.orders_30d * 1.0 / NULLIF(e.cust_30d, 0), 3)
FROM ec e
UNION ALL
SELECT 'cost', 'discount_30d', e.discount_30d,
       ROUND(e.discount_30d * 100.0 / NULLIF(e.gmv_30d, 0), 2)
FROM ec e
UNION ALL
SELECT 'finance', 'deposit_total', f.deposit_total,
       ROUND(f.deposit_total / NULLIF(f.fin_cust, 0), 2)
FROM fn f
UNION ALL
SELECT 'risk', 'npl_pct', f.npl_pct, NULL FROM fn f
UNION ALL
SELECT 'hr', 'headcount', h.headcount, h.avg_salary FROM hr h
UNION ALL
SELECT 'hr', 'left_1y', h.left_1y,
       ROUND(h.left_1y * 100.0 / NULLIF(h.headcount, 0), 2)
FROM hr h
UNION ALL
SELECT 'hr', 'avg_perf', p.avg_perf, NULL FROM pf p
UNION ALL
SELECT 'efficiency', 'gmv_per_head',
       ROUND(e.gmv_30d / NULLIF(h.headcount, 0), 2),
       ROUND(e.gmv_30d / NULLIF(h.headcount, 0) * 12.0, 2)
FROM ec e
         CROSS JOIN hr h
ORDER BY 1, 2;
