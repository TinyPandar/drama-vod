import { ref } from "vue";
import { describe } from "./api";

/** 按钮动作的通用壳：busy 防重复点击，message / error 给页面显示。 */
export function useAction() {
  const busy = ref(false);
  const message = ref("");
  const error = ref(false);

  async function run(fn: () => Promise<string | void>) {
    busy.value = true;
    message.value = "";
    error.value = false;
    try {
      const done = await fn();
      if (done) message.value = done;
    } catch (e) {
      error.value = true;
      message.value = describe(e);
    } finally {
      busy.value = false;
    }
  }

  return { busy, message, error, run };
}
