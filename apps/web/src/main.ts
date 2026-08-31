import { createApp } from "vue";

import App from "./App.vue";
import { requireIdentity } from "./auth";
import "./style.css";

requireIdentity()
  .then(() => createApp(App).mount("#app"))
  .catch(() => {
    document.querySelector("#app")!.textContent = "无法完成登录";
  });
