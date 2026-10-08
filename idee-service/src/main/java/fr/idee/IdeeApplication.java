package fr.idee;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.WebApplicationType;
import java.time.Duration;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication
public class IdeeApplication {
 public static void main(String[] args) {
  if(System.getenv("MISTRAL_BATCH_LIMIT")!=null || System.getenv("MISTRAL_BATCH_ID")!=null) {
   descriptionBatch(); return;
  }
  String rewriteSlug=System.getenv("MISTRAL_REWRITE_SLUG");
  if (rewriteSlug!=null && !rewriteSlug.isBlank()) {
   rewriteOne(rewriteSlug);
   return;
  }
  if (!Boolean.parseBoolean(System.getenv("DATATOURISME_BATCH"))) {
   SpringApplication.run(IdeeApplication.class,args);
   return;
  }
  // A separate native import process: same mapper, quota, checkpoints and database.
  var app=new SpringApplication(IdeeApplication.class);
  app.setWebApplicationType(WebApplicationType.NONE);
  int result=1;
  var batchArgs=new java.util.ArrayList<>(java.util.List.of(args));
  batchArgs.add("--idee.jobs.scheduled-enabled=false");
  batchArgs.add("--idee.calendar-refresh-enabled=false");
  try (var context=app.run(batchArgs.toArray(String[]::new))) {
   var importer=context.getBean(DatatourismeImporter.class);
   var descriptions=context.getBean(OutingDescriptionWorker.class);
   var translations=context.getBean(OutingTranslationWorker.class);
   if (!importer.isEnabled() || !descriptions.isEnabled() || !translations.isEnabled())
    throw new IllegalStateException("Daily providers must be configured");
   var job=new DailyImportJob(importer,descriptions,translations,context.getBean(JdbcTemplate.class));
   importer.requestSync();
   Instant deadline=Instant.now().plus(Duration.ofHours(3));
   Instant nextLog=Instant.MIN;
   DailyImportJob.Progress progress=null;
   while (Instant.now().isBefore(deadline)) {
    progress=job.step();
    if (!Instant.now().isBefore(nextLog) || progress.complete()) {
     System.out.println("Traitement quotidien : "+progress);
     nextLog=Instant.now().plusSeconds(60);
    }
    if (progress.complete()) { result=0; break; }
    Thread.sleep(5000);
   }
   if (result!=0 && progress!=null && progress.imports()==0) {
    // Provider batches are asynchronous: keep their IDs and resume tomorrow.
    result=0;
    System.out.println("Fenetre quotidienne terminee ; batches restants repris demain : "+progress);
   } else if (result!=0) {
    System.err.println("DATAtourisme : delai maximal atteint ; reprise au prochain lancement.");
   }
  } catch (InterruptedException e) {
   Thread.currentThread().interrupt();
  } catch (Exception e) {
   // Keep credentials and cursor URLs out of batch error output.
   System.err.println("DATAtourisme : echec du traitement ("+e.getClass().getSimpleName()+").");
  }
  System.exit(result);
 }
 private static void rewriteOne(String slug) {
  var app=new SpringApplication(IdeeApplication.class);
  app.setWebApplicationType(WebApplicationType.NONE);
  int result=1;
  try (var context=app.run("--idee.openai.translations-enabled=false","--idee.datatourisme.enabled=false","--idee.calendar-refresh-enabled=false","--idee.mistral.auto-enabled=false")) {
   var rewritten=context.getBean(OutingDescriptions.class).generateFrench(slug);
   System.out.println(context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class).writeValueAsString(rewritten));
   result=0;
  } catch (org.springframework.web.server.ResponseStatusException e) {
   System.err.println("Generation refusee : "+e.getReason());
  } catch (Exception e) {
   System.err.println("Generation interrompue : "+e.getClass().getSimpleName());
  }
  System.exit(result);
 }
 private static void descriptionBatch() {
  var app=new SpringApplication(IdeeApplication.class);
  app.setWebApplicationType(WebApplicationType.NONE);
  int result=1;
  try(var context=app.run("--idee.openai.translations-enabled=false","--idee.datatourisme.enabled=false","--idee.calendar-refresh-enabled=false","--idee.mistral.auto-enabled=false")) {
   var db=context.getBean(JdbcTemplate.class);
   var mistral=context.getBean(MistralDescriptions.class);
   if(!mistral.isConfigured()) throw new IllegalStateException("Mistral key missing");
   var manager=context.getBean(org.springframework.transaction.PlatformTransactionManager.class);
   var batches=new OutingDescriptionBatches(db,mistral,manager);
   String existing=System.getenv("MISTRAL_BATCH_ID");
   java.util.UUID id;
   if(existing!=null) id=java.util.UUID.fromString(existing);
   else {
    int limit=Integer.parseInt(System.getenv("MISTRAL_BATCH_LIMIT"));
    if(limit<1 || limit>500) throw new IllegalArgumentException("Batch limit must be 1..500");
    var worker=new OutingDescriptionWorker(db,context.getBean(OutingDescriptions.class),mistral,manager,true);
    int missing=limit-(int)Math.min(limit,worker.readyCount());
    if(missing>0) worker.enqueueMissingFrench(missing);
    id=batches.submitNext(limit);
   }
   if(id==null) { System.out.println("MISTRAL_BATCH_RESULT={\"accepted\":0}"); result=0; }
   else {
    Instant deadline=Instant.now().plus(Duration.ofHours(26));
    do {
     batches.collect(id);
     var status=db.queryForMap("SELECT id,provider_id AS \"providerId\",state,total,succeeded,failed,completed_at AS \"completedAt\",last_error AS \"lastError\" FROM idee_description_batch WHERE id=?",id);
     System.out.println("MISTRAL_BATCH_RESULT="+context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class).writeValueAsString(status));
     if(status.get("completedAt")!=null || !Boolean.parseBoolean(System.getenv("MISTRAL_BATCH_WAIT"))) { result=0; break; }
     Thread.sleep(60000);
    } while(Instant.now().isBefore(deadline));
   }
  } catch(InterruptedException error) { Thread.currentThread().interrupt(); }
  catch(Exception error) { System.err.println("Batch Mistral interrompu : "+error.getClass().getSimpleName()); }
  System.exit(result);
 }
}
