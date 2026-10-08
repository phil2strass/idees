import { DOCUMENT } from "@angular/common";
import { HttpInterceptorFn } from "@angular/common/http";
import { inject, InjectionToken, REQUEST } from "@angular/core";
import { timeout } from "rxjs/operators";

export const SITE_ORIGIN = new InjectionToken<string>("SITE_ORIGIN", {
  providedIn: "root",
  factory: () => {
    const request = inject(REQUEST, { optional: true });
    return request ? new URL(request.url).origin : inject(DOCUMENT).location.origin;
  },
});
export const API_ORIGIN = new InjectionToken<string>("API_ORIGIN", {
  providedIn: "root", factory: () => inject(SITE_ORIGIN),
});

// Absolute URLs plus the server origin map let hydration reuse public API responses.
export const apiInterceptor: HttpInterceptorFn = (request, next) => {
  if (!request.url.startsWith("/api/")) return next(request);
  return next(request.clone({ url: inject(API_ORIGIN) + request.url })).pipe(timeout(10000));
};
