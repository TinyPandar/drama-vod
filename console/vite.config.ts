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
      "/api": "http://localhost:8080"
    }
  }
});
