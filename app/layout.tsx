import type { Metadata } from 'next';
import './globals.css';

export const metadata: Metadata = {
  title: '知脉｜AI 工作知识图谱维护助手',
  description: '让每份办公资料找到上下文，并让知识维护过程可追溯。',
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="zh-CN">
      <body>{children}</body>
    </html>
  );
}
