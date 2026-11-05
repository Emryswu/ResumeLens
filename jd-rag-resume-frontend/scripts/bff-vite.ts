import type { Plugin } from "vite";
import { serveBff } from "./bff-ingress.ts";

export const bffIngress: Plugin = {
  name: "bff-socket-ingress",
  enforce: "pre",
  configureServer(server) {
    server.middlewares.use((req, res, next) => { serveBff(req, res, next).catch(next); });
  },
};
