import { DOCUMENT } from "@angular/common";
import {
  DestroyRef,
  Injectable,
  REQUEST,
  inject,
  signal,
  effect,
} from "@angular/core";
import { takeUntilDestroyed } from "@angular/core/rxjs-interop";
import { NavigationEnd, Router } from "@angular/router";
import { filter } from "rxjs/operators";
import { Outing } from "./outing.model";
import { TRANSLATIONS } from "./translations";

export const LANGUAGES = [
  { code: "fr", name: "Français" },
  { code: "en", name: "English" },
  { code: "de", name: "Deutsch" },
  { code: "it", name: "Italiano" },
  { code: "nl", name: "Nederlands" },
  { code: "es", name: "Español" },
] as const;
export type Language = (typeof LANGUAGES)[number]["code"];
export function languageFromPath(path: string): Language {
  const first = path.split(/[/?#]/)[1];
  return LANGUAGES.find((l) => l.code === first)?.code || "fr";
}

@Injectable({ providedIn: "root" })
export class LanguageService {
  private router = inject(Router);
  private document = inject(DOCUMENT);
  private request = inject(REQUEST, { optional: true });
  private pageUrls: Partial<Record<Language, string>> | null = null;
  readonly languages = LANGUAGES;
  readonly current = signal<Language>(
    languageFromPath(
      this.request
        ? new URL(this.request.url).pathname
        : this.document.location.pathname,
    ),
  );
  constructor() {
    this.router.events
      .pipe(
        filter((e) => e instanceof NavigationEnd),
        takeUntilDestroyed(inject(DestroyRef)),
      )
      .subscribe((e) =>
        this.current.set(languageFromPath(e.urlAfterRedirects)),
      );
    effect(() => (this.document.documentElement.lang = this.current()));
  }
  t(key: string, params: Record<string, string | number> = {}): string {
    const index = LANGUAGES.findIndex((l) => l.code === this.current()) - 1;
    const text = index < 0 ? key : TRANSLATIONS[key]?.[index] || key;
    return text.replace(/\{(\w+)\}/g, (match, name: string) =>
      String(params[name] ?? match),
    );
  }
  path(path: string, language: Language = this.current()): string {
    const withoutLanguage =
      path.replace(/^\/(fr|en|de|it|nl|es)(?=\/|$|\?|#)/, "") || "/";
    return language === "fr"
      ? withoutLanguage
      : "/" + language + (withoutLanguage === "/" ? "" : withoutLanguage);
  }
  switchTo(language: string) {
    if (!LANGUAGES.some((l) => l.code === language)) return;
    const tree = this.router.parseUrl(this.router.url);
    const path = this.router.url.split(/[?#]/)[0];
    void this.router.navigate(
      [
        this.pageUrls?.[language as Language] ||
          this.path(path, language as Language),
      ],
      {
        queryParams: tree.queryParams,
        fragment: tree.fragment ?? undefined,
      },
    );
  }
  outingPath(outing: Outing, language: Language = this.current()): string {
    return (
      outing.urls?.[language] ||
      this.path(
        outing.url || "/sorties/" + encodeURIComponent(outing.slug),
        language,
      )
    );
  }
  pageLinks(
    path: string,
    origin: string,
    urls?: Partial<Record<string, string>>,
  ) {
    this.clearPageLinks();
    // Missing translations retain the French path under the language prefix.
    const french = urls?.["fr"] || this.path(path, "fr");
    this.pageUrls = Object.fromEntries(
      LANGUAGES.map((language) => [
        language.code,
        urls?.[language.code] || this.path(french, language.code),
      ]),
    );
    const add = (rel: string, href: string, language?: string) => {
      const link = this.document.createElement("link");
      link.rel = rel;
      link.href = href;
      if (language) link.hreflang = language;
      link.setAttribute("data-idee-language", "");
      this.document.head.appendChild(link);
    };
    add("canonical", origin + this.pageUrls[this.current()]);
    for (const language of LANGUAGES)
      add("alternate", origin + this.pageUrls[language.code], language.code);
    add("alternate", origin + this.pageUrls["fr"], "x-default");
  }
  clearPageLinks() {
    this.pageUrls = null;
    this.document
      .querySelectorAll('link[rel="canonical"], link[data-idee-language]')
      .forEach((link) => link.remove());
  }
  localize(outing: Outing): Outing {
    const translation = outing.translations?.find(
      (t) => t.language === this.current(),
    );
    if (this.current() === "fr" || !translation)
      return { ...outing, contentLanguage: "fr" };
    return {
      ...outing,
      title: translation.title || outing.title,
      description: translation.description_longue || outing.description,
      description_courte:
        translation.description_courte || outing.description_courte,
      summary: translation.description_courte || outing.summary,
      contentLanguage: this.current(),
    };
  }
}
