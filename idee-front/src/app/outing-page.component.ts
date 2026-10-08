import {
  Component,
  ElementRef,
  ViewChild,
  DestroyRef,
  inject,
  signal,
  computed,
  effect,
  OnDestroy,
  PLATFORM_ID,
  RESPONSE_INIT,
  REQUEST,
} from "@angular/core";
import { DOCUMENT, isPlatformBrowser } from "@angular/common";
import { HttpClient, HttpErrorResponse } from "@angular/common/http";
import { ActivatedRoute, Router, RouterLink } from "@angular/router";
import { Meta, Title } from "@angular/platform-browser";
import { takeUntilDestroyed } from "@angular/core/rxjs-interop";
import { TablerIconsModule } from "angular-tabler-icons";
import { Subject, combineLatest, of, throwError } from "rxjs";
import { catchError, map, startWith, switchMap, tap } from "rxjs/operators";
import { Outing, SourceContact, SourceLocation } from "./outing.model";
import { OutingPresentation } from "./outing-presentation";
import { SITE_ORIGIN } from "./rendering";
import { localDay, occurrenceRange, shiftDay, weekStart } from "./outing-calendar";

@Component({
  standalone: true,
  imports: [RouterLink, TablerIconsModule],
  templateUrl: "./outing-page.component.html",
  styleUrl: "./outing-page.component.scss",
})
export class OutingPageComponent
  extends OutingPresentation
  implements OnDestroy
{
  private http = inject(HttpClient);
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private document = inject(DOCUMENT);
  private title = inject(Title);
  private meta = inject(Meta);
  private destroyRef = inject(DestroyRef);
  private reload = new Subject<void>();
  private browser = isPlatformBrowser(inject(PLATFORM_ID));
  private response = inject(RESPONSE_INIT, { optional: true });
  private serverRequest = inject(REQUEST, { optional: true });
  private siteOrigin = inject(SITE_ORIGIN);
  private rawOuting = signal<Outing | null>(null);
  readonly outing = computed(() => {
    const o = this.rawOuting();
    return o ? this.i18n.localize(o) : null;
  });
  loading = signal(true);
  errorStatus = signal(0);
  calendarOpen = signal(false);
  selectedDay = signal("");
  calendarMonth = signal("");
  private readonly now = new Date();
  @ViewChild("imageDialog") private imageDialog?: ElementRef<HTMLDialogElement>;
  enlargedImage = signal<Outing["images"][number] | null>(null);
  private previousOverflow: string | null = null;
  coverFailed = signal(false);
  shareMessage = signal("");
  showShareField = signal(false);
  shareUrl = signal("");
  readonly introduction = computed(() => {
    const outing = this.outing();
    if (!outing) return "";
    if (outing.contentLanguage !== "fr") return outing.description;
    const french = outing.descriptions?.find((t) => t.language === "fr");
    const source = outing.sourceDetails?.translations.find(
      (t) => t.language === "fr",
    );
    return (
      french?.description_longue ||
      french?.description ||
      source?.description ||
      source?.summary ||
      (outing.sourceDetails
        ? this.t("Description en français non disponible.")
        : outing.description || outing.summary)
    );
  });
  readonly visitorContacts = computed(() =>
    (this.outing()?.sourceDetails?.contacts || [])
      .filter(
        (c) =>
          !["creator", "publisher"].includes(c.role) &&
          (c.name || c.channels.length),
      )
      .filter(
        (c, _, all) =>
          c.name ||
          !all.some(
            (other) =>
              other.name &&
              other.role === c.role &&
              JSON.stringify(other.channels) === JSON.stringify(c.channels),
          ),
      )
      .filter(
        (c, i, all) =>
          all.findIndex(
            (other) => JSON.stringify(other) === JSON.stringify(c),
          ) === i,
      ),
  );
  readonly documents = computed(() =>
    (this.outing()?.sourceDetails?.resources || [])
      .filter((r) => this.safeUrl(r.url))
      .filter(
        (r, i, all) => all.findIndex((other) => other.url === r.url) === i,
      ),
  );
  readonly sourceProducers = computed(() => [...new Set(
    (this.outing()?.sourceDetails?.contacts || [])
      .filter((contact) => contact.role === "creator" && contact.name?.trim())
      .map((contact) => contact.name!.trim()),
  )].join(" · "));
  readonly sourceUpdated = computed(() => {
    const source = this.outing()?.sourceDetails;
    const day = (source?.updatedOn || source?.updatedAt || "").slice(0, 10);
    if (!/^\d{4}-\d{2}-\d{2}$/.test(day)) return null;
    const date = new Date(day + "T12:00:00Z");
    if (Number.isNaN(date.getTime())) return null;
    return {
      day,
      label: new Intl.DateTimeFormat(this.i18n.current(), {dateStyle: "long", timeZone: "UTC"}).format(date),
    };
  });
  readonly termGroups = computed(() => {
    const terms = this.outing()?.sourceDetails?.terms || [];
    const names: Record<string, string> = {
      hasTheme: "Thèmes",
      hasGeographicReach: "Rayonnement",
      availableLanguage: "Langues d’accueil",
      isDedicatedTo: "Publics",
      hasAudience: "Publics",
      hasClientTarget: "Publics",
      isEquippedWith: "Équipements",
      hasFacility: "Services",
      hasPractice: "Activités",
    };
    return Object.entries(names)
      .map(([property, title]) => ({
        title: this.t(title),
        property,
        labels: [
          ...new Set(
            terms
              .filter((t) => t.property === property)
              .map((t) =>
                property === "availableLanguage"
                  ? this.languageName(t.label || t.key || "")
                  : t.label || "",
              )
              .filter(Boolean),
          ),
        ],
      }))
      .filter((g) => g.labels.length);
  });
  readonly dateRanges = computed(() => (this.outing()?.occurrences || [])
    .map((occurrence) => ({ occurrence, range: occurrenceRange(occurrence) }))
    .sort((a, b) => new Date(a.occurrence.startsAt).getTime() - new Date(b.occurrence.startsAt).getTime()));
  readonly manyDates = computed(() => this.dateRanges().length > 4);
  readonly today = computed(() => localDay(this.now, this.outing()?.occurrences[0]?.timezone || "Europe/Paris"));
  readonly thisWeek = computed(() => weekStart(this.today()));
  readonly visibleDates = computed(() => {
    const dates = this.dateRanges();
    if (!this.manyDates()) return dates.map((d) => d.occurrence);
    const selected = this.calendarOpen() ? this.selectedDay() : "";
    const first = selected || this.thisWeek();
    const last = selected || shiftDay(this.thisWeek(), 6);
    return dates.filter(({ range }) => range[0] <= last && range[1] >= first).map((d) => d.occurrence);
  });
  readonly calendarBounds = computed(() => {
    const dates = this.dateRanges();
    return {
      first: dates.reduce((first, d) => first < d.range[0] ? first : d.range[0], dates[0]?.range[0] || this.today()).slice(0, 7),
      last: dates.reduce((last, d) => last > d.range[1] ? last : d.range[1], dates[0]?.range[1] || this.today()).slice(0, 7),
    };
  });
  readonly calendarDays = computed(() => {
    if (!this.calendarMonth()) return [];
    const first = this.calendarMonth() + "-01";
    const start = weekStart(first);
    const monthEnd = new Date(first + "T12:00:00Z");
    monthEnd.setUTCMonth(monthEnd.getUTCMonth() + 1);
    monthEnd.setUTCDate(0);
    const end = shiftDay(weekStart(monthEnd.toISOString().slice(0, 10)), 6);
    const days = [];
    for (let day = start; day <= end; day = shiftDay(day, 1)) {
      const available = this.dateRanges().some(({ range }) => range[0] <= day && range[1] >= day);
      days.push({ key: day, number: Number(day.slice(8)), inMonth: day.startsWith(this.calendarMonth()), available });
    }
    return days;
  });
  readonly weekdays = computed(() => Array.from({ length: 7 }, (_, i) => new Intl.DateTimeFormat(this.i18n.current(), {
    weekday: "short", timeZone: "UTC",
  }).format(new Date(shiftDay("2024-01-01", i) + "T12:00:00Z"))));

  calendarLabel(day: string, month = false) {
    return new Intl.DateTimeFormat(this.i18n.current(), {
      ...(month ? { month: "long" as const, year: "numeric" as const } : { weekday: "long" as const, day: "numeric" as const, month: "long" as const, year: "numeric" as const }),
      timeZone: "UTC",
    }).format(new Date(day + "T12:00:00Z"));
  }
  toggleCalendar() {
    if (this.calendarOpen()) { this.calendarOpen.set(false); return; }
    const dates = this.dateRanges();
    const first = dates.find(({ range }) => range[1] >= this.today());
    const day = first ? (first.range[0] < this.today() ? this.today() : first.range[0]) : dates[0]?.range[0] || this.today();
    this.selectedDay.set(day);
    this.calendarMonth.set(day.slice(0, 7));
    this.calendarOpen.set(true);
  }
  moveCalendarMonth(offset: number) {
    const date = new Date(this.calendarMonth() + "-01T12:00:00Z");
    date.setUTCMonth(date.getUTCMonth() + offset);
    this.calendarMonth.set(date.toISOString().slice(0, 7));
  }

  constructor() {
    super();
    effect(() => {
      const outing = this.outing();
      if (outing) this.updateMetadata(outing);
    });
    combineLatest([this.route.paramMap, this.reload.pipe(startWith(undefined))])
      .pipe(
        tap(() => {
          this.closeImage();
          this.loading.set(true);
          this.rawOuting.set(null);
          this.errorStatus.set(0);
          this.calendarOpen.set(false);
          this.selectedDay.set("");
          this.calendarMonth.set("");
          this.coverFailed.set(false);
          this.shareMessage.set("");
          this.showShareField.set(false);
          this.title.setTitle(
            this.t("Votre sortie") +
              " · " +
              this.t("Idées de sorties en Alsace"),
          );
        }),
        switchMap(([params]) =>
          this.http
            .get<Outing>(
              "/api/outing-by-path?path=" +
                encodeURIComponent(
                  "/" +
                    this.route.snapshot.pathFromRoot
                      .flatMap((route) =>
                        route.url.map((segment) => segment.path),
                      )
                      .join("/"),
                ),
            )
            .pipe(
              // Older APIs have no path resolver. Only legacy routes can use
              // the technical-slug endpoint; geographic paths must be validated in full.
              catchError((error: HttpErrorResponse) =>
                error.status === 404 && !params.has("department")
                  ? this.http.get<Outing>(
                      "/api/outings/" +
                        encodeURIComponent(params.get("slug") || ""),
                    )
                  : throwError(() => error),
              ),
              map((outing) => ({ outing, status: 0 })),
              catchError((error: HttpErrorResponse) =>
                of({ outing: null, status: error.status || 503 }),
              ),
            ),
        ),
        takeUntilDestroyed(),
      )
      .subscribe(({ outing, status }) => {
        this.loading.set(false);
        this.errorStatus.set(status);
        this.rawOuting.set(outing);
        if (outing) {
          // Metadata is updated by the language-aware effect below.
        } else {
          if (this.response) this.response.status = status === 404 ? 404 : 503;
          this.clearMetadata();
          this.title.setTitle(
            status === 404
              ? this.t("Sortie introuvable") +
                  " · " +
                  this.t("Idées de sorties en Alsace")
              : this.t("Sortie indisponible") +
                  " · " +
                  this.t("Idées de sorties en Alsace"),
          );
          this.meta.updateTag({
            name: "description",
            content: this.t("Retrouvez toutes nos idées de sorties en Alsace."),
          });
        }
        if (this.browser)
          requestAnimationFrame(() => {
            if (!this.destroyRef.destroyed)
              this.document
                .getElementById("outing-title")
                ?.focus({ preventScroll: true });
          });
      });
  }

  private updateMetadata(outing: Outing) {
    const title = outing.title + " · " + this.t("Idées de sorties en Alsace");
    const outingPath = this.outingUrl(outing);
    const url = this.siteOrigin + outingPath;
    const currentPath = this.serverRequest
      ? new URL(this.serverRequest.url).pathname
      : this.router.url.split(/[?#]/)[0];
    if (currentPath !== outingPath && this.response) {
      this.response.status = 301;
      const headers = new Headers(this.response.headers);
      headers.set(
        "Location",
        outingPath +
          (this.serverRequest ? new URL(this.serverRequest.url).search : ""),
      );
      this.response.headers = headers;
    }
    if (currentPath !== outingPath && this.browser) {
      void this.router.navigate([outingPath], {
        replaceUrl: true,
        queryParamsHandling: "preserve",
        preserveFragment: true,
      });
    }
    this.title.setTitle(title);
    this.shareUrl.set(url);
    this.meta.updateTag({
      name: "description",
      content:
        outing.description_courte ||
        outing.summary ||
        outing.description.slice(0, 160),
    });
    this.meta.updateTag({ property: "og:title", content: outing.title });
    this.meta.updateTag({
      property: "og:description",
      content: outing.description_courte || outing.summary,
    });
    this.meta.updateTag({ property: "og:url", content: url });
    this.meta.updateTag({ property: "og:type", content: "article" });
    this.meta.removeTag('property="og:image"');
    if (this.safeUrl(outing.images[0]?.url))
      this.meta.updateTag({
        property: "og:image",
        content: new URL(outing.images[0].url, this.siteOrigin).href,
      });
    this.i18n.pageLinks(outingPath, this.siteOrigin, outing.urls);
  }

  retry() {
    this.reload.next();
  }
  safeUrl(value?: string | null) {
    if (!value) return null;
    if (/^\/api\/media\/images\/(?:[a-f0-9]{64}|[a-z0-9]+(?:-[a-z0-9]+)*-(?:[a-f0-9]{16}|[a-f0-9]{64}))\.(jpg|png|gif|webp|avif)$/.test(value)) return value;
    try {
      const url = new URL(value);
      return ["https:", "http:"].includes(url.protocol) &&
        !url.username &&
        !url.password
        ? url.href
        : null;
    } catch {
      return null;
    }
  }
  languageName(code: string) {
    if (code === "und") return this.t("Langue non précisée");
    try {
      return (
        new Intl.DisplayNames([this.i18n.current()], { type: "language" }).of(
          code,
        ) || code
      );
    } catch {
      return code;
    }
  }
  contactRole(role: string) {
    return (
      (
        {
          contact: this.t("Renseignements"),
          booking: this.t("Contact réservation"),
          owner: this.t("Responsable du lieu"),
          administrative: this.t("Contact administratif"),
          communication: this.t("Communication"),
        } as Record<string, string>
      )[role] || this.t("Contact")
    );
  }
  channelLabel(kind: string) {
    return (
      (
        {
          telephone: this.t("Téléphone"),
          email: this.t("Courriel"),
          homepage: this.t("Site web"),
          fax: "Fax",
        } as Record<string, string>
      )[kind] || this.t("Coordonnée")
    );
  }
  contactHref(channel: SourceContact["channels"][number]) {
    const value = channel.value.trim();
    if (channel.kind === "homepage") return this.safeUrl(value);
    if (channel.kind === "telephone" && /^[+\d\s().-]+$/.test(value))
      return "tel:" + value.replace(/[\s().-]/g, "");
    if (
      channel.kind === "email" &&
      /^[^\s@?&#]+@[^\s@?&#]+\.[^\s@?&#]+$/.test(value)
    )
      return "mailto:" + encodeURIComponent(value);
    return null;
  }
  resourceLabel(url: string) {
    try {
      return decodeURIComponent(
        new URL(url).pathname.split("/").pop() ||
          this.t("Consulter la ressource"),
      );
    } catch {
      return this.t("Consulter la ressource");
    }
  }
  locationMapUrl(location: SourceLocation) {
    const coords =
      typeof location.latitude === "number" &&
      typeof location.longitude === "number" &&
      Number.isFinite(location.latitude) &&
      Number.isFinite(location.longitude);
    const query = coords
      ? `${location.latitude},${location.longitude}`
      : [
          location.name,
          location.address,
          location.postalCode,
          location.city,
          "France",
        ]
          .filter(Boolean)
          .join(", ");
    return (
      "https://www.google.com/maps/search/?api=1&query=" +
      encodeURIComponent(query)
    );
  }
  private mapQuery(o: Outing) {
    if (
      typeof o.latitude === "number" &&
      typeof o.longitude === "number" &&
      Number.isFinite(o.latitude) &&
      Number.isFinite(o.longitude) &&
      Math.abs(o.latitude) <= 90 &&
      Math.abs(o.longitude) <= 180
    ) {
      return `${o.latitude},${o.longitude}`;
    }
    return [o.placeName, o.address, o.postalCode, o.city, "France"]
      .filter(Boolean)
      .join(", ");
  }
  mapUrl(o: Outing) {
    return (
      "https://www.google.com/maps/dir/?api=1&destination=" +
      encodeURIComponent(this.mapQuery(o))
    );
  }
  priceLabel(price: Outing["prices"][number]) {
    if (price.type === "free") return this.t("Gratuit");
    return price.amount === null
      ? this.t("À confirmer")
      : new Intl.NumberFormat(this.i18n.current(), {
          style: "currency",
          currency: price.currency,
        }).format(price.amount);
  }
  async share() {
    try {
      await navigator.clipboard.writeText(this.shareUrl());
      this.shareMessage.set(this.t("Lien copié. Vous pouvez le partager."));
    } catch {
      this.showShareField.set(true);
      this.shareMessage.set(
        this.t("Copiez le lien ci-dessous pour partager cette sortie."),
      );
    }
  }
  private clearMetadata() {
    this.i18n.clearPageLinks();
    for (const property of [
      "og:title",
      "og:description",
      "og:url",
      "og:type",
      "og:image",
    ])
      this.meta.removeTag(`property="${property}"`);
  }
  openImage(image: Outing["images"][number]) {
    if (!this.browser || !this.imageDialog) return;
    this.enlargedImage.set(image);
    this.previousOverflow = this.document.body.style.overflow;
    this.document.body.style.overflow = "hidden";
    this.imageDialog.nativeElement.showModal();
    this.imageDialog.nativeElement.focus({ preventScroll: true });
  }

  closeImage() {
    if (this.browser && this.imageDialog?.nativeElement.open)
      this.imageDialog.nativeElement.close();
    this.enlargedImage.set(null);
    if (this.previousOverflow !== null) {
      this.document.body.style.overflow = this.previousOverflow;
      this.previousOverflow = null;
    }
  }

  closeImageOnBackdrop(event: MouseEvent) {
    if (event.target === event.currentTarget) this.closeImage();
  }

  ngOnDestroy() {
    this.closeImage();
    this.clearMetadata();
  }
}
