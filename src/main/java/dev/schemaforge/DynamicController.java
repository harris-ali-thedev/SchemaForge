package dev.schemaforge;

import java.util.Map;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class DynamicController {
    private final QueryEngine engine;
    public DynamicController(QueryEngine engine) { this.engine=engine; }
    @GetMapping("/{table}")
    public QueryEngine.Page list(@PathVariable String table, @RequestParam MultiValueMap<String,String> query, Authentication auth) { return engine.list(table,query,auth); }
    @GetMapping("/{table}/{id}")
    public Map<String,Object> item(@PathVariable String table, @PathVariable String id, Authentication auth) { return engine.item(table,id,auth); }
    @PostMapping("/{table}")
    public ResponseEntity<Map<String,Object>> insert(@PathVariable String table, @RequestBody Map<String,Object> body, Authentication auth) {
        return ResponseEntity.status(201).body(engine.insert(table,body,auth));
    }
    @PatchMapping("/{table}/{id}")
    public ResponseEntity<Void> patch(@PathVariable String table, @PathVariable String id, @RequestBody Map<String,Object> body, Authentication auth) {
        engine.update(table,id,body,false,auth); return ResponseEntity.noContent().build();
    }
    @PutMapping("/{table}/{id}")
    public ResponseEntity<Void> put(@PathVariable String table, @PathVariable String id, @RequestBody Map<String,Object> body, Authentication auth) {
        engine.update(table,id,body,true,auth); return ResponseEntity.noContent().build();
    }
    @DeleteMapping("/{table}/{id}")
    public ResponseEntity<Void> delete(@PathVariable String table, @PathVariable String id, Authentication auth) {
        engine.delete(table,id,auth); return ResponseEntity.noContent().build();
    }
    @GetMapping("/{table}/{id}/{related}")
    public QueryEngine.Page related(@PathVariable String table, @PathVariable String id, @PathVariable String related,
            @RequestParam MultiValueMap<String,String> query, Authentication auth) { return engine.related(table,id,related,query,auth); }
}
