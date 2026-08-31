/** Match the public registration route consistently, including ingress-added trailing slashes. */
export function isRegistrationPath(pathname = window.location.pathname): boolean {
  return pathname.replace(/\/+$/, '') === '/register';
}
