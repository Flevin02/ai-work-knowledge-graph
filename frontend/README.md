# 前端

这里承载知脉 Next.js 工作台，包括页面入口、图谱组件、前端类型、后端 API 客户端和虚构演示图谱。

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

前端不保存模型密钥、数据库路径或上传文件；真实资料导入和持久化由 `../backend/` 的 Java 服务负责。
