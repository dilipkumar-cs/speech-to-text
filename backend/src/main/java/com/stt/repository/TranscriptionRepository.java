package com.stt.repository;

import com.stt.entity.Transcription;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TranscriptionRepository extends JpaRepository<Transcription, Long> {

    @Query("""
            select t from Transcription t
            where lower(t.title) like lower(concat('%', :q, '%'))
               or lower(t.content) like lower(concat('%', :q, '%'))
            """)
    Page<Transcription> search(@Param("q") String query, Pageable pageable);

    @Query("select coalesce(sum(t.durationSeconds), 0) from Transcription t")
    long totalDurationSeconds();
}
