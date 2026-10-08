import { inject } from "@angular/core";
import { LanguageService } from "./language.service";
import { Outing, Occurrence } from "./outing.model";

export class OutingPresentation {
  readonly i18n = inject(LanguageService);
  t(key: string, params: Record<string, string | number> = {}) {
    return this.i18n.t(key, params);
  }
  outingUrl(o: Outing): string {
    return this.i18n.outingPath(o);
  }

  illustration(o: Outing) {
    const category = o.categories[0]?.slug;
    return (
      "assets/outing-" +
      (category === "culture" || category === "marche"
        ? "village"
        : category === "famille"
          ? "vosges"
          : "vines") +
      ".svg"
    );
  }
  datePart(occurrence: Occurrence, part: "day" | "month") {
    return new Intl.DateTimeFormat(this.i18n.current(), {
      [part]: part === "day" ? "numeric" : "short",
      timeZone: occurrence.timezone,
    }).format(new Date(occurrence.startsAt));
  }
  normalize(s: string) {
    return s
      .normalize("NFD")
      .replace(/[\u0300-\u036f]/g, "")
      .toLowerCase();
  }
  price(o: Outing) {
    if (!o.prices.length) return this.t("Tarif à confirmer");
    if (o.prices.every((p) => p.type === "free")) return this.t("Gratuit");
    const p = o.prices.find((p) => p.amount !== null && p.amount > 0);
    return p
      ? this.t("Dès ") +
          new Intl.NumberFormat(this.i18n.current(), {
            style: "currency",
            currency: p.currency,
            maximumFractionDigits: 0,
          }).format(p.amount!)
      : this.t("Tarif à confirmer");
  }
  next(o: Outing) {
    return o.occurrences.find((x) => x.status !== "cancelled" && new Date(x.endsAt).getTime() > Date.now());
  }
  dateLabel(o: Outing) {
    if (o.kind === "permanent")
      return this.t("Toute l’année · accès à vérifier");
    const x = this.next(o);
    return x
      ? this.formatDate(x.startsAt, x.timezone)
      : this.t("Prochaines dates à confirmer");
  }
  formatDate(value: string, tz = "Europe/Paris") {
    return new Intl.DateTimeFormat(this.i18n.current(), {
      weekday: "short",
      day: "numeric",
      month: "short",
      year: "numeric",
      timeZone: tz,
    }).format(new Date(value));
  }
  timeLabel(x: Occurrence) {
    if (x.startTimeKnown === false && x.endTimeKnown === false)
      return this.t("Horaires non communiqués");
    if (x.allDay) return this.t("Toute la journée");
    const format = new Intl.DateTimeFormat(this.i18n.current(), {
      hour: "2-digit",
      minute: "2-digit",
      timeZone: x.timezone,
    });
    if (x.endTimeKnown === false)
      return (
        this.t("À partir de ") +
        format.format(new Date(x.startsAt)) +
        this.t(" · fin non communiquée")
      );
    if (x.startTimeKnown === false)
      return (
        this.t("Jusqu’à ") +
        format.format(new Date(x.endsAt)) +
        this.t(" · début non communiqué")
      );
    return (
      format.format(new Date(x.startsAt)) +
      " – " +
      format.format(new Date(x.endsAt))
    );
  }
  endLabel(x: Occurrence) {
    const end = new Date(x.endsAt);
    if (x.allDay || x.endTimeKnown === false) end.setTime(end.getTime() - 1);
    return this.formatDate(end.toISOString(), x.timezone);
  }
}
