import { ApplicationConfig, inject, mergeApplicationConfig } from "@angular/core";
import { HTTP_TRANSFER_CACHE_ORIGIN_MAP } from "@angular/common/http";
import { provideServerRendering, RenderMode, withRoutes } from "@angular/ssr";
import { appConfig } from "./app.config";
import { API_ORIGIN, SITE_ORIGIN } from "./rendering";

const apiOrigin = new URL(process.env["API_ORIGIN"] || "http://127.0.0.1:8087").origin;
const serverConfig: ApplicationConfig = {
  providers: [
    provideServerRendering(withRoutes([{ path: "**", renderMode: RenderMode.Server }])),
    { provide: API_ORIGIN, useValue: apiOrigin },
    { provide: HTTP_TRANSFER_CACHE_ORIGIN_MAP,
      useFactory: () => ({ [apiOrigin]: inject(SITE_ORIGIN) }) },
  ],
};
export const config = mergeApplicationConfig(appConfig, serverConfig);
