import { ConversationProvider } from "./components/ConversationProvider";
import type { Metadata } from "next";
import "./globals.css";
import "./workspace.css";
import { ExtensionBridgeHost } from "./components/ExtensionBridgeHost";

export const metadata: Metadata = {
  title: "ResumeLens · RAG 简历匹配",
  description: "基于阿里 GTE 与可配置生成模型的可解释简历职位匹配工作台。",
  icons: { icon: "/favicon.svg", shortcut: "/favicon.svg" },
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="zh-CN"><body><ConversationProvider><ExtensionBridgeHost />{children}</ConversationProvider></body></html>;
}
