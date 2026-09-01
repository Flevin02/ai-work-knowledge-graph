import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const fixtureRoot = path.join(repositoryRoot, 'fixture/document-association-v2');
const annotationPath = path.join(fixtureRoot, 'annotations.json');
const annotations = JSON.parse(fs.readFileSync(annotationPath, 'utf8'));
const failures = [];

function check(condition, message) {
    if (!condition) {
        failures.push(message);
    }
}

function checkUnique(values, label) {
    check(new Set(values).size === values.length, `${label} 存在重复值`);
}

function normalizePair(leftDocumentId, rightDocumentId) {
    return [leftDocumentId, rightDocumentId].sort().join('::');
}

check(annotations.datasetVersion === 'document-association-eval-v2', 'datasetVersion 必须为 document-association-eval-v2');
check(annotations.status === 'frozen', '资料集状态必须为 frozen');
check(annotations.documents.length === 11, `文档数量应为 11，实际为 ${annotations.documents.length}`);
check(annotations.expectedRelations.length === 5, `正例关系数量应为 5，实际为 ${annotations.expectedRelations.length}`);
check(annotations.negativePairs.length === 2, `负例数量应为 2，实际为 ${annotations.negativePairs.length}`);
checkUnique(annotations.documents.map((item) => item.documentId), 'documentId');
checkUnique(annotations.expectedRelations.map((item) => item.relationId), 'relationId');
checkUnique(annotations.negativePairs.map((item) => item.caseId), '负例 caseId');
checkUnique(annotations.retrievalCases.map((item) => item.caseId), '召回 caseId');

const documentsById = new Map();
for (const document of annotations.documents) {
    const documentPath = path.join(fixtureRoot, document.path);
    check(fs.existsSync(documentPath), `文档不存在：${document.path}`);
    if (!fs.existsSync(documentPath)) {
        continue;
    }

    const content = fs.readFileSync(documentPath, 'utf8');
    documentsById.set(document.documentId, { ...document, content });

    const expectedKind = path.extname(documentPath) === '.txt' ? 'txt' : 'markdown';
    check(document.kind === expectedKind, `${document.documentId} 的 kind 与扩展名不一致`);
    check(['general', 'prd'].includes(document.documentType), `${document.documentId} 使用了不支持的 documentType`);
    check(Array.isArray(document.expectedTags) && document.expectedTags.length === 0, `${document.documentId} 的 v2 资料不参与标签评估`);
}

const relationTypeWhitelist = new Set([
    'related_to',
    'references',
    'supports',
    'updates',
    'conflicts_with'
]);
const normalizedRelations = new Set();

for (const relation of annotations.expectedRelations) {
    check(relationTypeWhitelist.has(relation.relationType), `${relation.relationId} 使用了非法关系类型`);
    check(documentsById.has(relation.sourceDocumentId), `${relation.relationId} 的主体文档不存在`);
    check(documentsById.has(relation.targetDocumentId), `${relation.relationId} 的客体文档不存在`);
    check(relation.sourceDocumentId !== relation.targetDocumentId, `${relation.relationId} 不能自关联`);

    const normalizedRelation = `${normalizePair(relation.sourceDocumentId, relation.targetDocumentId)}::${relation.relationType}`;
    check(!normalizedRelations.has(normalizedRelation), `${relation.relationId} 与已有规范化关系重复`);
    normalizedRelations.add(normalizedRelation);

    const allowedEvidenceDocumentIds = new Set([relation.sourceDocumentId, relation.targetDocumentId]);
    check(relation.evidences.length >= 2, `${relation.relationId} 至少需要两端各一条证据`);
    for (const evidence of relation.evidences) {
        check(allowedEvidenceDocumentIds.has(evidence.documentId), `${relation.relationId} 的证据引用了关系两端之外的文档`);
        const evidenceDocument = documentsById.get(evidence.documentId);
        check(Boolean(evidenceDocument), `${relation.relationId} 的证据文档不存在`);
        if (evidenceDocument) {
            check(evidenceDocument.content.includes(evidence.quote), `${relation.relationId} 缺少逐字关系证据：${evidence.quote}`);
        }
    }
}

const normalizedNegativePairs = new Set();
for (const negativePair of annotations.negativePairs) {
    check(negativePair.expectedRelationType === 'none', `${negativePair.caseId} 的预期必须为 none`);
    check(documentsById.has(negativePair.leftDocumentId), `${negativePair.caseId} 的左侧文档不存在`);
    check(documentsById.has(negativePair.rightDocumentId), `${negativePair.caseId} 的右侧文档不存在`);
    check(typeof negativePair.note === 'string' && negativePair.note.length > 10, `${negativePair.caseId} 必须说明成为负例的歧义原因`);

    const normalizedPair = normalizePair(negativePair.leftDocumentId, negativePair.rightDocumentId);
    check(!normalizedNegativePairs.has(normalizedPair), `${negativePair.caseId} 与已有负例重复`);
    normalizedNegativePairs.add(normalizedPair);

    const conflictsWithPositive = annotations.expectedRelations.some(
        (relation) => normalizePair(relation.sourceDocumentId, relation.targetDocumentId) === normalizedPair
    );
    check(!conflictsWithPositive, `${negativePair.caseId} 与正例关系冲突`);
}

const expectationWhitelist = new Set(['missed', 'recalled', 'partial', 'empty']);
const missedCases = [];
for (const retrievalCase of annotations.retrievalCases) {
    check(documentsById.has(retrievalCase.sourceDocumentId), `${retrievalCase.caseId} 的当前文档不存在`);
    check(expectationWhitelist.has(retrievalCase.contentRecallExpectation), `${retrievalCase.caseId} 的 contentRecallExpectation 非法`);

    const candidateDocumentIds = [
        ...retrievalCase.expectedCandidateDocumentIds,
        ...retrievalCase.hardNegativeDocumentIds
    ];
    checkUnique(candidateDocumentIds, `${retrievalCase.caseId} 的候选文档`);
    for (const candidateDocumentId of candidateDocumentIds) {
        check(documentsById.has(candidateDocumentId), `${retrievalCase.caseId} 的候选文档不存在：${candidateDocumentId}`);
        check(candidateDocumentId !== retrievalCase.sourceDocumentId, `${retrievalCase.caseId} 包含当前文档自身`);
    }

    if (retrievalCase.contentRecallExpectation === 'empty') {
        check(retrievalCase.expectedCandidateDocumentIds.length === 0, `${retrievalCase.caseId} 孤立用例不得有期望候选`);
    }
    if (retrievalCase.contentRecallExpectation === 'missed') {
        check(retrievalCase.expectedCandidateDocumentIds.length > 0, `${retrievalCase.caseId} 漏召回用例必须声明期望候选`);
        missedCases.push(retrievalCase.caseId);
    }
}
check(missedCases.length >= 4, `漏召回用例至少需要 4 个才能测量语义补充价值，实际 ${missedCases.length}`);

const conflictsExpectation = annotations.retrievalCases.filter(
    (item) => item.contentRecallExpectation === 'recalled'
).length;
check(conflictsExpectation >= 1, '至少需要 1 个内容可召回对照用例，证明内容通道在 v2 上仍然工作');

const specialCases = annotations.specialCases;
check(documentsById.get(specialCases.longDocumentId)?.content.length > 1500, '长文档必须超过默认 1500 字符分片基线');
check(documentsById.has(specialCases.isolatedDocumentId), '孤立文档不存在');
check(
    annotations.retrievalCases.some((item) => item.sourceDocumentId === specialCases.isolatedDocumentId),
    '孤立文档必须有自己的召回用例'
);

// 词面互斥抽查：漏召回用例的正文不得出现期望候选文档标题的任意连续 6 字片段（显式互引防线）
for (const retrievalCase of annotations.retrievalCases) {
    if (retrievalCase.contentRecallExpectation !== 'missed') {
        continue;
    }
    const sourceDocument = documentsById.get(retrievalCase.sourceDocumentId);
    for (const candidateDocumentId of retrievalCase.expectedCandidateDocumentIds) {
        const candidateDocument = documentsById.get(candidateDocumentId);
        const sourceText = sourceDocument.content.replace(/[^一-龥A-Za-z0-9]/g, '');
        const candidateTitle = candidateDocument.content
            .split('\n')[0]
            .replace(/[^一-龥A-Za-z0-9]/g, '');
        for (let start = 0; start + 6 <= candidateTitle.length; start++) {
            const fragment = candidateTitle.slice(start, start + 6);
            check(
                !sourceText.includes(fragment),
                `${retrievalCase.caseId} 声称漏召回，但正文出现了 ${candidateDocumentId} 标题片段：${fragment}`
            );
        }
    }
}

if (failures.length > 0) {
    console.error(`文档关联 v2 固定资料校验失败：${failures.length} 项`);
    for (const failure of failures) {
        console.error(`- ${failure}`);
    }
    process.exit(1);
}

console.log('文档关联 v2 固定资料校验通过');
console.log(JSON.stringify({
    datasetVersion: annotations.datasetVersion,
    documents: annotations.documents.length,
    expectedRelations: annotations.expectedRelations.length,
    negativePairs: annotations.negativePairs.length,
    retrievalCases: annotations.retrievalCases.length,
    missedRecallCases: missedCases.length,
    longDocumentChars: documentsById.get(specialCases.longDocumentId).content.length
}, null, 2));
