/** The sign-in form's request, shared by the home page and the workbench. */
import type { AuthForm, AuthMode } from "./components/LoginPage";
import type { AuthResponse } from "./lib/api";

type Request = <T>(path: string, init: RequestInit, options: { auth: false }) => Promise<T>;

/** Logs in or registers; only the login fields are sent for a login. The caller stores the access token. */
export function signInRequest(request: Request, mode: AuthMode, form: AuthForm): Promise<AuthResponse> {
  const path = mode === "login" ? "/api/auth/login" : "/api/auth/register";
  const body = mode === "login" ? { username: form.username, password: form.password } : form;
  return request<AuthResponse>(path, { method: "POST", body: JSON.stringify(body) }, { auth: false });
}
