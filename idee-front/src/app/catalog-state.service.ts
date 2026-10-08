import { Injectable, signal } from "@angular/core";
@Injectable({ providedIn: "root" })
export class CatalogState {
  query = signal("");
  category = signal("");
  department = signal("");
  city = signal("");
  period = signal("today");
  date = signal("");
  freeOnly = signal(false);
  reset() {
    this.query.set("");
    this.category.set("");
    this.department.set("");
    this.city.set("");
    this.period.set("today");
    this.date.set("");
    this.freeOnly.set(false);
  }
}
