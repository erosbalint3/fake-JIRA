package com.fakejira.approval;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TaskApprovalRepository extends JpaRepository<TaskApproval, Long> {

    List<TaskApproval> findByTaskIdOrderByCreatedAtAsc(Long taskId);

    @Query("select a from TaskApproval a join fetch a.task t join fetch t.project where a.approver.id = :userId "
            + "and a.state = com.fakejira.approval.TaskApproval.State.PENDING order by a.createdAt")
    List<TaskApproval> pendingFor(@Param("userId") Long userId);

    @Modifying
    @Query("delete from TaskApproval a where a.task.id = :taskId")
    void deleteForTask(@Param("taskId") Long taskId);

    @Modifying
    @Query("delete from TaskApproval a where a.state = com.fakejira.approval.TaskApproval.State.PENDING "
            + "and a.approver.id = :userId and a.task.id in (select t.id from Task t where t.project.id = :projectId)")
    void deletePendingFor(@Param("projectId") Long projectId, @Param("userId") Long userId);
}
