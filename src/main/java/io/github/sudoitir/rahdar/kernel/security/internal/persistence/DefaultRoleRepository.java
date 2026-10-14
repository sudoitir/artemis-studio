package io.github.sudoitir.rahdar.kernel.security.internal.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DefaultRoleRepository extends JpaRepository<DefaultRoleEntity, String> {}
