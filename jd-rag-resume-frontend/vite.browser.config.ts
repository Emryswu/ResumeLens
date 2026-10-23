import { defineConfig } from "vite";
import vinext from "vinext";

// Browser regressions use vinext's Node dev server with the isolated fixture's dynamic backend URL.
// The ordinary production build continues to validate the Cloudflare configuration.
export default defineConfig({ plugins: [vinext()] });
