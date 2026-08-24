import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const fixtureRoot = path.join(repositoryRoot, 'fixture/document-association-v1');
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

check(annotations.datasetVersion === 'document-association-eval-v1', 'datasetVersion 必须为 document-association-eval-v1');
check(annotations.status === 'frozen', '资料集状态必须为 frozen');
check(annotations.documents.length === 12, `文档数量应为 12，实际为 ${annotations.documents.length}`);
check(annotations.expectedRelations.length === 7, `正例关系数量应为 7，实际为 ${annotations.expectedRelations.length}`);
check(annotations.negativePairs.length === 5, `负例数量应为 5，实际为 ${annotations.negativePairs.length}`);
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
    check(['general', 'prd'].includes(document.documentType), `${document.documentId} 使用了当前导入接口不支持的 documentType`);

    for (const tag of document.expectedTags) {
        check(content.includes(tag.evidenceQuote), `${document.documentId} 缺少标签证据：${tag.evidenceQuote}`);
    }
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
    for (const evidence of relation.evidences) {
        const evidenceDocument = documentsById.get(evidence.documentId);
        check(allowedEvidenceDocumentIds.has(evidence.documentId), `${relation.relationId} 的证据引用了关系两端之外的文档`);
        check(Boolean(evidenceDocument), `${relation.relationId} 的证据文档不存在`);
        if (evidenceDocument) {
            check(evidenceDocument.content.includes(evidence.quote), `${relation.relationId} 缺少逐字关系证据：${evidence.quote}`);
        }
    }
}

const coveredRelationTypes = new Set(annotations.expectedRelations.map((item) => item.relationType));
for (const relationType of relationTypeWhitelist) {
    check(coveredRelationTypes.has(relationType), `缺少 ${relationType} 正例`);
}

const normalizedNegativePairs = new Set();
for (const negativePair of annotations.negativePairs) {
    check(negativePair.expectedRelationType === 'none', `${negativePair.caseId} 的预期必须为 none`);
    check(documentsById.has(negativePair.leftDocumentId), `${negativePair.caseId} 的左侧文档不存在`);
    check(documentsById.has(negativePair.rightDocumentId), `${negativePair.caseId} 的右侧文档不存在`);

    const normalizedPair = normalizePair(negativePair.leftDocumentId, negativePair.rightDocumentId);
    check(!normalizedNegativePairs.has(normalizedPair), `${negativePair.caseId} 与已有负例重复`);
    normalizedNegativePairs.add(normalizedPair);

    const conflictsWithPositive = annotations.expectedRelations.some(
        (relation) => normalizePair(relation.sourceDocumentId, relation.targetDocumentId) === normalizedPair
    );
    check(!conflictsWithPositive, `${negativePair.caseId} 与正例关系冲突`);

    const allowedEvidenceDocumentIds = new Set([negativePair.leftDocumentId, negativePair.rightDocumentId]);
    for (const evidence of negativePair.evidences) {
        const evidenceDocument = documentsById.get(evidence.documentId);
        check(allowedEvidenceDocumentIds.has(evidence.documentId), `${negativePair.caseId} 的证据引用了负例两端之外的文档`);
        check(Boolean(evidenceDocument), `${negativePair.caseId} 的证据文档不存在`);
        if (evidenceDocument) {
            check(evidenceDocument.content.includes(evidence.quote), `${negativePair.caseId} 缺少逐字负例证据：${evidence.quote}`);
        }
    }
}

for (const retrievalCase of annotations.retrievalCases) {
    check(documentsById.has(retrievalCase.sourceDocumentId), `${retrievalCase.caseId} 的当前文档不存在`);
    const candidateDocumentIds = [
        ...retrievalCase.expectedCandidateDocumentIds,
        ...retrievalCase.hardNegativeDocumentIds
    ];
    checkUnique(candidateDocumentIds, `${retrievalCase.caseId} 的候选文档`);

    for (const candidateDocumentId of candidateDocumentIds) {
        check(documentsById.has(candidateDocumentId), `${retrievalCase.caseId} 的候选文档不存在：${candidateDocumentId}`);
        check(candidateDocumentId !== retrievalCase.sourceDocumentId, `${retrievalCase.caseId} 包含当前文档自身`);
    }
}

const specialCases = annotations.specialCases;
check(documentsById.get(specialCases.tableDocumentId)?.content.includes('| 场地 |'), '表格场景缺少预期 Markdown 表头');
check(documentsById.get(specialCases.longDocumentId)?.content.length > 1500, '长文档必须超过默认 1500 字符分片基线');
check(documentsById.has(specialCases.duplicateImportDocumentId), '重复导入文档不存在');
check(documentsById.has(specialCases.versionChange.oldDocumentId), '旧版本文档不存在');
check(documentsById.has(specialCases.versionChange.newDocumentId), '新版本文档不存在');
check(
    documentsById.get(specialCases.versionChange.oldDocumentId)?.logicalDocumentKey
        === documentsById.get(specialCases.versionChange.newDocumentId)?.logicalDocumentKey,
    '版本变化文档的 logicalDocumentKey 不一致'
);
check(documentsById.has(specialCases.isolatedDocumentId), '孤立文档不存在');

const associationSchema = JSON.parse(fs.readFileSync(
    path.join(repositoryRoot, 'docs/design/document-association-output-schema-v1.json'),
    'utf8'
));
const tagSchema = JSON.parse(fs.readFileSync(
    path.join(repositoryRoot, 'docs/design/document-tag-output-schema-v1.json'),
    'utf8'
));

check(associationSchema.$schema === 'https://json-schema.org/draft/2020-12/schema', '文档关联 Schema 未声明 Draft 2020-12');
check(associationSchema.$id === 'document-association-v1', '文档关联 Schema 版本不正确');
check(associationSchema.required?.includes('evidences'), '文档关联 Schema 缺少 evidences');
check(associationSchema.required?.includes('decisions'), '文档关联 Schema 缺少 decisions');
const decisionSchema = associationSchema.properties?.decisions?.items;
const relationTypeEnum = decisionSchema?.properties?.relationType?.enum ?? [];
const directionEnum = decisionSchema?.properties?.direction?.enum ?? [];
check(
    ['related_to', 'references', 'supports', 'updates', 'conflicts_with', 'none']
        .every((relationType) => relationTypeEnum.includes(relationType)),
    '文档关联 Schema 缺少关系类型白名单或 none'
);
check(
    ['current_to_candidate', 'candidate_to_current', 'symmetric', 'none']
        .every((direction) => directionEnum.includes(direction)),
    '文档关联 Schema 缺少方向枚举'
);
check(tagSchema.$schema === 'https://json-schema.org/draft/2020-12/schema', '标签 Schema 未声明 Draft 2020-12');
check(tagSchema.$id === 'document-tag-v1', '标签 Schema 版本不正确');
check(tagSchema.required?.includes('summary'), '标签 Schema 缺少 summary');
check(tagSchema.required?.includes('tags'), '标签 Schema 缺少 tags');
check(tagSchema.required?.includes('evidences'), '标签 Schema 缺少 evidences');

if (failures.length > 0) {
    console.error(`文档关联固定资料校验失败：${failures.length} 项`);
    for (const failure of failures) {
        console.error(`- ${failure}`);
    }
    process.exit(1);
}

console.log('文档关联固定资料校验通过');
console.log(JSON.stringify({
    datasetVersion: annotations.datasetVersion,
    documents: annotations.documents.length,
    expectedRelations: annotations.expectedRelations.length,
    negativePairs: annotations.negativePairs.length,
    retrievalCases: annotations.retrievalCases.length,
    relationTypes: [...coveredRelationTypes].sort(),
    longDocumentChars: documentsById.get(specialCases.longDocumentId).content.length
}, null, 2));
