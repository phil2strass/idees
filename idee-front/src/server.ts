import { AngularAppEngine } from "@angular/ssr";
import { createNodeRequestHandler, isMainModule, writeResponseToNodeResponse } from "@angular/ssr/node";
import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { dirname, extname, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

const publicOrigin = new URL(process.env["PUBLIC_ORIGIN"] || "http://localhost:4000").origin;
const browserFolder = resolve(dirname(fileURLToPath(import.meta.url)), "../browser");
const contentTypes: Record<string, string> = {
  ".js": "text/javascript", ".css": "text/css", ".svg": "image/svg+xml",
  ".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".webp": "image/webp",
  ".ico": "image/x-icon", ".woff": "font/woff", ".woff2": "font/woff2", ".ttf": "font/ttf",
};
const engine = new AngularAppEngine({ allowedHosts: [new URL(publicOrigin).hostname] });

// Kept separate from the listener so tests can render real HTML without starting a server.
export async function renderRequest(request: Request): Promise<Response> {
  if (!["GET", "HEAD"].includes(request.method))
    return new Response("Method not allowed", { status: 405, headers: { Allow: "GET, HEAD" } });
  const url = new URL(request.url);
  if (url.pathname === "/_health") return new Response("ok");
  if (url.pathname.startsWith("/api/")) return new Response("Not found", { status: 404 });
  // Nginx handles assets in production; this also supports the standalone Node preview.
  if (url.pathname.startsWith("/assets/") || contentTypes[extname(url.pathname)]) {
    try {
      const file = resolve(browserFolder, "." + decodeURIComponent(url.pathname));
      if (!file.startsWith(browserFolder + sep)) return new Response("Not found", { status: 404 });
      const body = await readFile(file);
      return new Response(request.method === "HEAD" ? null : body, {
        headers: { "Content-Type": contentTypes[extname(file)] || "application/octet-stream" },
      });
    } catch { return new Response("Not found", { status: 404 }); }
  }
  // Canonicals and internal rendering must not trust an incoming Host/forwarded header.
  const safeRequest = new Request(publicOrigin + url.pathname + url.search, { method: request.method });
  try {
    const response = await engine.handle(safeRequest);
    if (!response) return new Response("Not found", { status: 404 });
    response.headers.set("Cache-Control", "no-store");
    return response;
  } catch {
    console.error("SSR: rendering failed");
    return new Response("Page momentanément indisponible.", {
      status: 503, headers: { "Content-Type": "text/plain; charset=utf-8", "Cache-Control": "no-store" },
    });
  }
}

export const reqHandler = createNodeRequestHandler(async (req, res) => {
  if (!["GET", "HEAD"].includes(req.method || "GET")) {
    res.writeHead(405, { Allow: "GET, HEAD" }).end();
    return;
  }
  try {
    const request = new Request(new URL(req.url || "/", publicOrigin), { method: req.method || "GET" });
    await writeResponseToNodeResponse(await renderRequest(request), res);
  } catch {
    if (!res.headersSent) res.writeHead(503, { "Content-Type": "text/plain; charset=utf-8" });
    res.end("Page momentanément indisponible.");
  }
});

if (isMainModule(import.meta.url)) {
  const port = Number(process.env["PORT"] || 4000);
  createServer(reqHandler).listen(port, process.env["HOST"] || "127.0.0.1", () => {
    console.log(`Angular SSR listening on port ${port}`);
  });
}
