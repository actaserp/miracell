package mes.domain.repository.approval;

import mes.domain.entity.approval.TB_BBSINFO;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public interface BBSINFORepository extends JpaRepository<TB_BBSINFO, Integer> {

    //뭔가 mssql이랑 호환이 잘언되는듯? postgrel는 잘되는뎅 계속 에러나서 걍 네이티브 쿼리로 ㄱㄱ
    //성능은 안나올순 있지만 못 느낄정도일듯?
    @Query(value = """
    SELECT ROW_NUMBER() OVER (ORDER BY BBSSEQ desc) AS num, * FROM TB_BBSINFO
    WHERE BBSSUBJECT LIKE CONCAT('%', :searchKeyword ,'%')
    AND BBSFRDATE <= GETDATE()
    AND BBSTODATE >= DATEADD(DAY, -1, GETDATE())
    ORDER BY BBSSEQ desc 
    OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY
    """,
            countQuery = "SELECT COUNT(*) FROM TB_BBSINFO WHERE BBSFRDATE <= GETDATE()\n" +
                    "AND BBSTODATE >= DATEADD(DAY, -1, GETDATE())" +
                    "AND BBSSUBJECT LIKE CONCAT('%', :searchKeyword ,'%')",
            nativeQuery = true)
    List<Map<String, Object>> findAllWithPagination(@Param("offset") int offset, @Param("size") int size, @Param("searchKeyword") String searchKeyword);



    @Query(value = """
    SELECT COUNT(*) FROM TB_BBSINFO
    WHERE BBSFRDATE <= GETDATE()
    AND BBSTODATE >= DATEADD(DAY, -1, GETDATE())
    AND BBSSUBJECT LIKE CONCAT('%', :searchKeyword ,'%')
    """,
    nativeQuery = true)
    long countWithPagination(@Param("searchKeyword") String searchKeyword);

    Optional<TB_BBSINFO> findByBBSSEQ(Integer bbsseq);
}
