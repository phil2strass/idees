package fr.idee;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CatalogPeriodTest {
 @Test void periodsUseExclusiveEndAndMondayWeeks() {
   LocalDate friday=LocalDate.of(2026,9,25);
   assertArrayEquals(new LocalDate[]{friday,friday.plusDays(1)},CatalogController.periodRange("today",friday));
   assertArrayEquals(new LocalDate[]{LocalDate.of(2026,9,26),LocalDate.of(2026,9,28)},CatalogController.periodRange("weekend",friday));
   assertArrayEquals(new LocalDate[]{LocalDate.of(2026,9,21),LocalDate.of(2026,9,28)},CatalogController.periodRange("week",friday));
   for(int day=26;day<=27;day++) {
     var weekend=LocalDate.of(2026,9,day);
     assertArrayEquals(new LocalDate[]{LocalDate.of(2026,9,26),LocalDate.of(2026,9,28)},CatalogController.periodRange("weekend",weekend));
     assertArrayEquals(new LocalDate[]{LocalDate.of(2026,9,28),LocalDate.of(2026,10,5)},CatalogController.periodRange("next-week",weekend));
   }
 }
 @Test void nextWeekCrossesYearBoundary() {
   assertArrayEquals(new LocalDate[]{LocalDate.of(2026,12,28),LocalDate.of(2027,1,4)},CatalogController.periodRange("next-week",LocalDate.of(2026,12,27)));
 }
 @Test void allDatesAndPermanentHaveNoRange() {
   assertNull(CatalogController.periodRange("",LocalDate.now()));
   assertNull(CatalogController.periodRange("permanent",LocalDate.now()));
   assertThrows(org.springframework.web.server.ResponseStatusException.class,()->CatalogController.periodRange("bad",LocalDate.now()));
 }
}
