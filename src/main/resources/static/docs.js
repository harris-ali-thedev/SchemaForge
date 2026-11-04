"use strict";
document.getElementById("credentials").addEventListener("submit", async event => {
  event.preventDefault();
  const status = document.getElementById("status");
  const tokenInput = document.getElementById("token");
  const credential = tokenInput.value.trim().replace(/^Bearer\s+/i, "");
  const bearer = document.getElementById("kind").value === "bearer";
  const headers = bearer ? {Authorization: `Bearer ${credential}`} : {"X-API-KEY": credential};
  tokenInput.value = "";
  try {
    const response = await fetch("/openapi.json", {headers, cache: "no-store"});
    if (!response.ok) throw new Error(`Documentation request failed (${response.status})`);
    const spec = await response.json();
    window.SwaggerUIBundle({
      spec, dom_id: "#swagger-ui", persistAuthorization: false,
      requestInterceptor: request => {
        if (new URL(request.url, location.origin).origin === location.origin) {
          delete request.headers.Authorization;
          delete request.headers["X-API-KEY"];
          Object.assign(request.headers, headers);
        }
        return request;
      }
    });
    status.textContent = "Documentation loaded. Reload this page to clear credentials.";
  } catch (error) {
    document.getElementById("swagger-ui").replaceChildren();
    status.textContent = error.message;
  }
});
