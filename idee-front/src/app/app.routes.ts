import { Routes, UrlMatcher } from "@angular/router";
// Only supported languages consume a prefix; unknown prefixes remain 404s.
const languagePrefix: UrlMatcher = (segments) =>
  /^(en|de|it|nl|es)$/.test(segments[0]?.path || "")
    ? { consumed: [segments[0]] }
    : null;
const pages: Routes = [
  {
    path: "",
    pathMatch: "full",
    loadComponent: () =>
      import("./catalog.component").then((m) => m.CatalogComponent),
  },
  {
    path: "sorties/:slug",
    loadComponent: () =>
      import("./outing-page.component").then((m) => m.OutingPageComponent),
  },
  {
    path: ":department/:city/:slug",
    loadComponent: () =>
      import("./outing-page.component").then((m) => m.OutingPageComponent),
  },
];
export const routes: Routes = [
  { matcher: languagePrefix, children: pages },
  ...pages,
  {
    path: "**",
    loadComponent: () =>
      import("./not-found.component").then((m) => m.NotFoundComponent),
  },
];
