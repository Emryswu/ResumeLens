import { parseArgs } from "node:util";
import { loadEnv } from "vite";

const { values } = parseArgs({ options: {
  hostname: { type: "string", short: "H" }, port: { type: "string", short: "p" },
} });
Object.assign(process.env, loadEnv("production", process.cwd(), ""));
const { startProdServer } = await import("vinext/server/prod-server");
const { serveBff } = await import("./bff-ingress.ts");
const { server } = await startProdServer({
  port: Number(values.port ?? process.env.PORT ?? 3000), host: values.hostname ?? "0.0.0.0",
});
const [render] = server.listeners("request");
server.removeListener("request", render);
server.on("request", (req, res) => {
  serveBff(req, res, () => render.call(server, req, res)).catch(() => {
    if (!res.headersSent) res.writeHead(500);
    res.end();
  });
});
