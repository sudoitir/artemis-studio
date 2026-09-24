package io.github.sudoitir.artemisstudio.feature.setupreview.internal.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SetupReviewRepository extends JpaRepository<SetupReviewEntity, UUID> {}
