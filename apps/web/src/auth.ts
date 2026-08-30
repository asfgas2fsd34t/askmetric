import Keycloak from "keycloak-js";

const keycloak = new Keycloak({
  url: import.meta.env.VITE_OIDC_URL ?? "http://localhost:8082",
  realm: import.meta.env.VITE_OIDC_REALM ?? "askmetric",
  clientId: import.meta.env.VITE_OIDC_CLIENT_ID ?? "askmetric-web",
});

export async function requireIdentity(): Promise<void> {
  const authenticated = await keycloak.init({
    onLoad: "login-required",
    pkceMethod: "S256",
    checkLoginIframe: false,
  });
  if (!authenticated) {
    await keycloak.login();
  }
}

export async function accessToken(): Promise<string> {
  if (!keycloak.authenticated) {
    throw new Error("Authentication is required");
  }
  await keycloak.updateToken(30);
  if (!keycloak.token) {
    throw new Error("Access token is unavailable");
  }
  return keycloak.token;
}

export function logout(): Promise<void> {
  return keycloak.logout({ redirectUri: window.location.origin });
}
