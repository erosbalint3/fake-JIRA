package com.fakejira.board;

import com.fakejira.board.BoardService.ColumnInput;
import com.fakejira.board.BoardService.ColumnResponse;
import com.fakejira.common.CurrentUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class BoardController {

    private final BoardService board;
    private final CurrentUser currentUser;

    public BoardController(BoardService board, CurrentUser currentUser) {
        this.board = board;
        this.currentUser = currentUser;
    }

    @GetMapping("/api/projects/{key}/columns")
    public List<ColumnResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        return board.list(currentUser.from(jwt), key);
    }

    @PostMapping("/api/projects/{key}/columns")
    public List<ColumnResponse> add(@AuthenticationPrincipal Jwt jwt, @PathVariable String key,
                                    @RequestBody ColumnInput input) {
        return board.add(currentUser.from(jwt), key, input);
    }

    @PutMapping("/api/columns/{id}")
    public List<ColumnResponse> update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                       @RequestBody ColumnInput input) {
        return board.update(currentUser.from(jwt), id, input);
    }

    /** direction: -1 moves the column left, 1 moves it right. */
    @PostMapping("/api/columns/{id}/move")
    public List<ColumnResponse> move(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                     @RequestParam int direction) {
        return board.move(currentUser.from(jwt), id, direction);
    }

    @DeleteMapping("/api/columns/{id}")
    public List<ColumnResponse> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return board.delete(currentUser.from(jwt), id);
    }
}
