import { afterEach, describe, expect, it, vi } from "vitest";

import { applyTheme, initialTheme, toggleTheme } from "./theme";

function memoryStorage() {
  const store = new Map<string, string>();
  return {
    getItem: (key: string) => store.get(key) ?? null,
    setItem: (key: string, value: string) => void store.set(key, value),
  };
}

function stubDocument() {
  const dataset: Record<string, string> = {};
  return { documentElement: { dataset } as Document["documentElement"] };
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("initialTheme", () => {
  it("用户选择优先于系统偏好", () => {
    const storage = memoryStorage();
    storage.setItem("askmetric-theme", "light");
    vi.stubGlobal("matchMedia", () => ({ matches: true }));
    expect(initialTheme(storage)).toBe("light");
  });

  it("无选择时跟随系统，默认浅色", () => {
    const storage = memoryStorage();
    vi.stubGlobal("matchMedia", (query: string) => ({ matches: query.includes("dark") }));
    expect(initialTheme(storage)).toBe("dark");
    vi.stubGlobal("matchMedia", () => ({ matches: false }));
    expect(initialTheme(storage)).toBe("light");
  });

  it("忽略非法存储值", () => {
    const storage = memoryStorage();
    storage.setItem("askmetric-theme", "neon");
    vi.stubGlobal("matchMedia", () => ({ matches: false }));
    expect(initialTheme(storage)).toBe("light");
  });
});

describe("applyTheme / toggleTheme", () => {
  it("写根节点 data-theme；未选择持久化时不写 localStorage", () => {
    const storage = memoryStorage();
    const document = stubDocument();
    applyTheme("dark", { storage, document });
    expect(document.documentElement.dataset.theme).toBe("dark");
    expect(storage.getItem("askmetric-theme")).toBeNull();
  });

  it("用户显式选择才持久化", () => {
    const storage = memoryStorage();
    const document = stubDocument();
    applyTheme("dark", { persist: true, storage, document });
    expect(storage.getItem("askmetric-theme")).toBe("dark");
  });

  it("切换主题返回新值、应用并持久化", () => {
    const storage = memoryStorage();
    const document = stubDocument();
    const next = toggleTheme("light", { storage, document });
    expect(next).toBe("dark");
    expect(document.documentElement.dataset.theme).toBe("dark");
    expect(storage.getItem("askmetric-theme")).toBe("dark");
  });
});
