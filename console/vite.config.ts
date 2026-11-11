import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

export default defineConfig({
  plugins: [vue()],
  base: "/vod-console/",
  build: {
    outDir: "../nginx/html/vod-console",
    emptyOutDir: true
  },
  server: {
    proxy: {
      // 默认走 compose 的 Nginx；直连本机 vod-api 时设 VOD_CONSOLE_PROXY=http://localhost:8001
      "/api": process.env.VOD_CONSOLE_PROXY || "http://localhost:8080"
    }
  }
});
