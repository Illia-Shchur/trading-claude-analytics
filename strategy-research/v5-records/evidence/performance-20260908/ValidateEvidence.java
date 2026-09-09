import java.nio.file.*;
import com.tradinganalytics.contracts.schema.ResearchSchemaRegistry;
import com.tradinganalytics.infrastructure.security.JsonHashes;
public class ValidateEvidence {
 public static void main(String[] args) throws Exception {
  var registry=ResearchSchemaRegistry.defaultRegistry(); int n=0;
  for(String s:Files.readAllLines(Path.of(args[0]))) {
   var p=Path.of(s); var v=JsonHashes.mapper().readTree(Files.readAllBytes(p));
   registry.validateKnownContractSchema(v);
   if(v.has("content_sha256") && !v.path("content_sha256").asText().equals(JsonHashes.ownHash(v))) throw new IllegalStateException("Hash mismatch: "+s);
   n++;
  }
  System.out.println("Validated contracts and canonical hashes: "+n);
 }
}
