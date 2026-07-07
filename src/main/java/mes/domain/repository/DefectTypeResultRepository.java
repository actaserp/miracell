package mes.domain.repository;

import mes.domain.entity.DefectTypeResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface DefectTypeResultRepository extends JpaRepository<DefectTypeResult, Integer> {

	List<DefectTypeResult> findBySourceTableNameAndSourceDataPk(String sourceTableName, Integer sourceDataPk);

	/** 재-확정(finish 재실행) 대비: 해당 세션(소스)의 기존 부적합 전삭제 → reinsert */
	@Transactional
	@Modifying
	@Query(value = "delete from defect_type_result " +
			"where \"SourceTableName\" = :src and \"SourceDataPk\" = :pk",
			nativeQuery = true)
	void deleteBySource(@Param("src") String sourceTableName, @Param("pk") Integer sourcePk);
}