package com.rydex.driver.matching;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface MatchJobRepository extends JpaRepository<MatchJob, UUID> {
}
