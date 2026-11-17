import { ref } from "vue";
import { explain } from "./api";

/**
 * 按钮动作的通用壳：busy 防重复点击；message 是给人看的说明，raw 是接口原文（只在失败时有），error 区分成败。
 * loaded 在第一次动作结束后变 true，页面用它区分「加载中」和「空列表」。
 */
export function useAction() {
  const busy = ref(false);
  const message = ref("");
  const raw = ref("");
  const error = ref(false);
  const loaded = ref(false);

  async function run(fn: () => Promise<string | void>) {
    busy.value = true;
    message.value = "";
    raw.value = "";
    error.value = false;
    try {
      const done = await fn();
      if (done) message.value = done;
    } catch (e) {
      const said = explain(e);
      error.value = true;
      message.value = said.text;
      raw.value = said.raw;
    } finally {
      busy.value = false;
      loaded.value = true;
    }
  }

  return { busy, message, raw, error, loaded, run };
}
