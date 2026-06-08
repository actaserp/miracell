package mes.domain.repository.iljin;

import mes.domain.entity.iljin.suju_detail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;


@Repository
public interface SuJuDetailRepository extends JpaRepository<suju_detail, Integer> {

  void deleteBySujuId(Integer sujuId);

}
