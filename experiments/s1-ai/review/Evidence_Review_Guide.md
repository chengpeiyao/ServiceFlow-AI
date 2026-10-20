# A2 诊断证据人工评分操作

本材料从已冻结的四组开发集中的 A0/A2 记录生成，仅评价诊断建议。输入、标签、Prompt 和知识均未调整，未发出新模型请求。

## 评审材料

`evidence-a2-v1/template.jsonl` 有 30 条原始 A2 建议，每条的 material 同时保存客户输入、模型建议、完整检索知识、A0 规则建议以及整份输出是否已被拒绝。`cases.json` 保留全部 20 个案例：1 个无建议，2 个整份受理输出被拒绝。整体被拒绝的建议可用于独立语义分析，但不能计为可用受理结果。

先复制 template.jsonl 为 reviewed.jsonl。只填写 scores、reviewer、notes；不得改 reviewId 或 material。默认所有评分为 null，表示尚未人工审核。建议由用户或熟悉业务的审核人逐条完成，有争议的记录交第二人复核。

| 评分字段 | 允许值与判定 |
|---|---|
| evidenceSupport | SUPPORTED：引用确实支持完整建议；PARTIALLY_SUPPORTED：只支持部分；UNSUPPORTED：未提供充分支持；CONTRADICTED：与证据矛盾；NO_CITATION：无引用 |
| applicabilityCorrect | true/false/null；核对产品版本、知识前置条件及是否承认尚待验证的条件 |
| addedValueOverRule | NONE：与 A0 相同或无可说明增益；CLEARER_ACTION：有依据且更明确可执行；NEW_SUPPORTED_STEP：规则未覆盖且有知识支持的新步骤；UNSUPPORTED_ADDITION：新增内容无充分支持；UNDECIDABLE：无法判断 |
| factHypothesisConfusion | true 表示把推测当已确认事实，false 表示未发现此问题；null 表示未评分 |

判定顺序：先看客户原文和只读产品/版本，再看建议引文与完整知识，核对结论是否越过证据、版本或前置条件，最后与 A0 建议比较。逐字照抄引用并不自动获得 SUPPORTED；语言更长也不自动获得新增价值。任何非空评分都必须填写审核人和理由。

## 汇总

在项目根目录执行（不调用模型）：

```powershell
python -X utf8 .\experiments\s1-ai\review\evidence_review.py summarize .\experiments\s1-ai\review\evidence-a2-v1 .\experiments\s1-ai\review\evidence-a2-v1\reviewed.jsonl .\experiments\s1-ai\review\evidence-a2-v1\reviewed-summary-v1.json
```

输出文件必须尚不存在，防止覆盖历史。允许只提交部分已审核条目，其他条目继续计入 UNSCORED；每个维度独立统计。分母为建议条目数，不是案例通过率，不得混用。`unscored-summary.json` 只是确认模板的全部 30 条仍未评分，不是质量验收结果。

工具核对原始结果与模板 SHA-256、资料一致性、标识唯一性、评分类型、审核人与理由。新数据版本应新建评审目录；若标签或任务口径调整，应在新版本统一评价各比较方案，不能改旧标签抬高本轮成绩。底稿中的合成知识不代表真实企业专家审批。
