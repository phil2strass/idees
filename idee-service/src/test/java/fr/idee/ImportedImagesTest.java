package fr.idee;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImportedImagesTest {
    @TempDir Path directory;
    static final byte[] PNG=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a9XcAAAAASUVORK5CYII=");

    @Test void descriptiveNamesAreSafeReadableAndBounded() {
        String hash="a".repeat(16);
        assertEquals("exposition-wurth-erstein-"+hash+".jpg",ImageNames.descriptive("Exposition Würth","Erstein",hash,".jpg"));
        assertEquals("sortie-"+hash+".png",ImageNames.descriptive("中文",null,hash,".png"));
        assertTrue(ImageNames.valid(ImageNames.descriptive("É".repeat(300),"Colmar",hash,".png")));
        assertFalse(ImageNames.valid("../photo-"+hash+".png"));
        assertFalse(ImageNames.valid("photo.svg"));
        assertFalse(ImageNames.valid("photo-"+hash+".png?test"));
    }

    @Test void validatesAddressesAndNeverServesSvgOrHtml() throws Exception {
        var images=new ImportedImages(mock(JdbcTemplate.class),directory.toString(),true);
        var disabled=new ImportedImages(mock(JdbcTemplate.class),directory.toString(),false);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,disabled::enqueueAllMissing);
        for (String url:List.of("file:///etc/passwd","http://127.0.0.1/a","http://[::1]/a","http://10.1.1.1/a",
                "http://169.254.169.254/a","http://[fd00::1]/a","https://user:password@example.org/a","https://example.org:8080/a"))
            assertThrows(ImportedImages.ImageFailure.class,()->images.validateUrl(URI.create(url)),url);
        assertEquals(".png",ImportedImages.imageExtension(PNG));
        for (String text:List.of("<svg xmlns='http://www.w3.org/2000/svg'/>","<html>Erreur</html>",""))
            assertThrows(ImportedImages.ImageFailure.class,()->ImportedImages.imageExtension(text.getBytes()));
        var controller=new ImportedImageController(images);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->controller.image("../secret"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->controller.image("a".repeat(64)+".png"));
    }

    @Test void boundsTheActualBodyEvenWithoutContentLength() {
        var body=ImportedImages.limitedBody(4);
        var subscription=mock(Flow.Subscription.class);
        body.onSubscribe(subscription);
        body.onNext(List.of(ByteBuffer.wrap(new byte[3])));
        body.onNext(List.of(ByteBuffer.wrap(new byte[2])));
        verify(subscription).cancel();
        assertThrows(java.util.concurrent.CompletionException.class,()->body.getBody().toCompletableFuture().join());
    }

    @Test void downloadsRedirectsAndRejectsHttpFailuresAndNonImages() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/image",exchange->{
            exchange.getResponseHeaders().add("Content-Type","image/png");
            exchange.sendResponseHeaders(200,PNG.length); exchange.getResponseBody().write(PNG); exchange.close();
        });
        server.createContext("/redirect",exchange->{
            exchange.getResponseHeaders().add("Location","/image"); exchange.sendResponseHeaders(302,-1); exchange.close();
        });
        server.createContext("/private",exchange->{
            exchange.getResponseHeaders().add("Location","http://169.254.169.254/secret"); exchange.sendResponseHeaders(302,-1); exchange.close();
        });
        server.createContext("/error",exchange->{exchange.sendResponseHeaders(404,-1); exchange.close();});
        server.createContext("/html",exchange->{
            byte[] html="<html>not an image</html>".getBytes();
            exchange.sendResponseHeaders(200,html.length); exchange.getResponseBody().write(html); exchange.close();
        });
        server.start();
        try {
            int port=server.getAddress().getPort();
            var images=new ImportedImages(mock(JdbcTemplate.class),directory.toString(),true) {
                @Override void validateUrl(URI uri) throws UnknownHostException {
                    if (!(uri.getHost().equals("127.0.0.1") && uri.getPort()==port)) super.validateUrl(uri);
                }
            };
            String base="http://127.0.0.1:"+port;
            assertArrayEquals(PNG,images.download(URI.create(base+"/redirect")));
            assertThrows(ImportedImages.ImageFailure.class,()->images.download(URI.create(base+"/error")));
            assertThrows(ImportedImages.ImageFailure.class,()->images.download(URI.create(base+"/html")));
            assertThrows(ImportedImages.ImageFailure.class,()->images.download(URI.create(base+"/private")));
        } finally { server.stop(0); }
    }
}
