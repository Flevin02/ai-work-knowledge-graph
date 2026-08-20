# 前端

这里承载知脉 Next.js 工作台，包括页面入口、图谱组件、前端类型和后端 API 客户端。图谱和来源资料均以 Java 后端数据为准。

## 启动

```bash
cd frontend
npm install
npm run dev
```

默认地址：`http://localhost:3010`

后端地址通过 `NEXT_PUBLIC_BACKEND_API_URL` 配置，示例见 [.env.example](.env.example)。

## 验证

```bash
npm run typecheck
npm run build
```

前端不保存模型密钥、数据库路径或上传文件；来源资料的导入和持久化由 `../backend/` 的 Java 服务负责。
