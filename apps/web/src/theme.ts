export type ThemePreference = "light" | "dark";

const STORAGE_KEY = "askmetric-theme";

/** 读取初始主题：用户选择优先，其次跟随系统，默认浅色。 */
export function initialTheme(storage: Pick<Storage, "getItem"> = safeLocalStorage()): ThemePreference {
  const stored = storage.getItem(STORAGE_KEY);
  if (stored === "light" || stored === "dark") {
    return stored;
  }
  return globalThis.matchMedia?.("(prefers-color-scheme: dark)").matches ? "dark" : "light";
}

/** 把主题应用到文档根节点；只有用户显式选择时才持久化，避免钉死系统偏好。 */
export function applyTheme(
  theme: ThemePreference,
  options: { persist?: boolean; storage?: Pick<Storage, "setItem">; document?: Pick<Document, "documentElement"> } = {},
): void {
  const target = options.document ?? globalThis.document;
  target.documentElement.dataset.theme = theme;
  if (!options.persist) {
    return;
  }
  try {
    (options.storage ?? safeLocalStorage()).setItem(STORAGE_KEY, theme);
  } catch {
    // 隐私模式等场景下持久化失败不影响当前会话。
  }
}

export function toggleTheme(
  current: ThemePreference,
  options: { persist?: boolean; storage?: Pick<Storage, "setItem">; document?: Pick<Document, "documentElement"> } = {},
): ThemePreference {
  const next: ThemePreference = current === "dark" ? "light" : "dark";
  applyTheme(next, { persist: true, ...options });
  return next;
}

function safeLocalStorage(): Pick<Storage, "getItem" | "setItem"> {
  try {
    return globalThis.localStorage;
  } catch {
    return {
      getItem: () => null,
      setItem: () => undefined,
    };
  }
}
