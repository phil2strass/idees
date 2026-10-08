package fr.idee;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="IDEE_DATATOURISME_DB_TEST",matches="isolated")
@SpringBootTest(properties={"idee.openai.translations-enabled=false","idee.mistral.auto-enabled=false","idee.datatourisme.enabled=false","idee.calendar-refresh-enabled=false"})
class PublicUrlsDatabaseTest {
    @Autowired JdbcTemplate db;
    @Autowired CatalogController catalog;
    @Autowired PublicRouteController routes;

    long outing(String slug, Long place, String status) {
        return db.queryForObject("INSERT INTO idee_outing(slug,title,summary,description,kind,status,place_id) VALUES(?,'Été : cœur & Würth !','Résumé','Description','event',?,?) RETURNING id",Long.class,slug,status,place);
    }
    String path(String slug) { return (String)catalog.outing(slug).get("url"); }

    @Test void stableLocalizedPathsAliasesAndPublication() throws Exception {
        assertTrue(db.queryForObject("SELECT current_schema()",String.class).startsWith("idee_datatourisme_test_"));
        assertEquals("ete-coeur-strasse",db.queryForObject("SELECT idee_url_slug(?)",String.class,"E\u0301té cœur STRAẞE"));
        long place=db.queryForObject("INSERT INTO idee_place(name,city,department) VALUES('Musée','Strasbourg','67') RETURNING id",Long.class);
        db.execute("SELECT idee_attach_city_insee('67','Strasbourg','67482')");
        assertEquals("67482",db.queryForObject("SELECT insee_code FROM idee_city WHERE id=(SELECT city_id FROM idee_place WHERE id=?)",String.class,place));
        long first=outing("url-first",place,"published");
        String original="/bas-rhin/strasbourg/ete-coeur-wurth";
        assertEquals(original,path("url-first"));
        assertEquals("url-first",routes.resolve(original));
        assertEquals("url-first",routes.resolve("/sorties/url-first"));
        assertThrows(ResponseStatusException.class,()->routes.resolve("/haut-rhin/strasbourg/ete-coeur-wurth"));
        assertThrows(ResponseStatusException.class,()->routes.resolve("/de/bas-rhin/strassburg/ete-coeur-wurth"));
        long second=outing("url-second",place,"published");
        assertEquals(original+"-"+second,path("url-second"));
        db.update("UPDATE idee_outing SET title='Titre corrigé par un import' WHERE id=?",first);
        assertEquals(original,path("url-first"));
        long draft=outing("url-draft",place,"draft");
        assertThrows(ResponseStatusException.class,()->routes.resolve("/sorties/url-draft"));
        db.update("UPDATE idee_outing SET status='published' WHERE id=?",draft);
        assertEquals("url-draft",routes.resolve(path("url-draft")));
        // Explicit editorial change reserves the previous path and keeps one canonical route.
        db.update("UPDATE idee_outing_url SET slug='exposition-wurth' WHERE outing_id=? AND language='fr'",first);
        db.execute("SELECT idee_publish_outing_url("+first+")");
        String current="/bas-rhin/strasbourg/exposition-wurth";
        assertEquals(current,path("url-first"));
        assertEquals("url-first",routes.resolve(original));
        var mvc=MockMvcBuilders.standaloneSetup(routes).build();
        mvc.perform(get("/api/public-page").header("X-Original-URI","/sorties/url-first"))
            .andExpect(status().isMovedPermanently()).andExpect(header().string("Location",current));
        mvc.perform(get("/api/public-page").header("X-Original-URI",original))
            .andExpect(status().isMovedPermanently()).andExpect(header().string("Location",current));
        mvc.perform(get("/api/public-page").header("X-Original-URI",current))
            .andExpect(status().isOk()).andExpect(header().string("X-Accel-Redirect","/_outing-shell.html"));
        mvc.perform(get("/api/outing-by-path").param("path",current))
            .andExpect(status().isOk()).andExpect(jsonPath("$.url").value(current));
        mvc.perform(get("/api/public-page").header("X-Original-URI","/bas-rhin/colmar/exposition-wurth"))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/public-page")).andExpect(status().isNotFound());
        mvc.perform(get("/api/public-page").header("X-Original-URI",current+"/"))
            .andExpect(status().isMovedPermanently()).andExpect(header().string("Location",current));
        // A source/technical slug change also retains its historical public URL.
        db.update("UPDATE idee_outing SET slug='url-first-renamed' WHERE id=?",first);
        assertEquals("url-first-renamed",routes.resolve("/sorties/url-first"));
        assertEquals(current,path("url-first-renamed"));
        // A former canonical path must never be reused by another event.
        long third=outing("url-third",place,"published");
        assertEquals(original+"-"+third,path("url-third"));
        // Translation drafts do not become public French pages.
        db.update("INSERT INTO idee_department_translation VALUES('67','de','Bas-Rhin','bas-rhin')");
        db.update("INSERT INTO idee_city_translation SELECT city_id,'de','Straßburg','strassburg' FROM idee_place WHERE id=?",place);
        db.update("INSERT INTO idee_outing_url VALUES(?,'de','ausstellung-wurth')",first);
        assertEquals(current,path("url-first-renamed"));
        // Missing geography remains reachable; later localization enables a readable URL.
        long missing=outing("url-no-place",null,"published");
        assertEquals("/sorties/url-no-place",path("url-no-place"));
        db.update("UPDATE idee_outing SET place_id=? WHERE id=?",place,missing);
        assertTrue(path("url-no-place").startsWith("/bas-rhin/strasbourg/"));
        db.update("UPDATE idee_outing SET status='archived' WHERE id=?",first);
        assertThrows(ResponseStatusException.class,()->routes.resolve(current));
        assertThrows(ResponseStatusException.class,()->routes.resolve(original));
        assertThrows(ResponseStatusException.class,()->routes.resolve("/sorties/url-first"));
    }
}
