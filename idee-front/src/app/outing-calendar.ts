import type { Occurrence } from "./outing.model";

export function localDay(value: Date, timezone = "Europe/Paris"): string {
  const parts = new Intl.DateTimeFormat("en-CA", {
    year: "numeric", month: "2-digit", day: "2-digit", timeZone: timezone,
  }).formatToParts(value);
  const part = (type: string) => parts.find((p) => p.type === type)!.value;
  return `${part("year")}-${part("month")}-${part("day")}`;
}

export function shiftDay(day: string, days: number): string {
  const value = new Date(day + "T12:00:00Z");
  value.setUTCDate(value.getUTCDate() + days);
  return value.toISOString().slice(0, 10);
}

export function weekStart(day: string): string {
  const weekday = new Date(day + "T12:00:00Z").getUTCDay();
  return shiftDay(day, -((weekday + 6) % 7));
}

export function occurrenceRange(occurrence: Occurrence): [string, string] {
  const start = new Date(occurrence.startsAt);
  // Occurrence ends are exclusive, including at midnight and across DST changes.
  const end = new Date(Math.max(start.getTime(), new Date(occurrence.endsAt).getTime() - 1));
  return [localDay(start, occurrence.timezone), localDay(end, occurrence.timezone)];
}
