package mes.domain.repository.approval;

import mes.domain.entity.approval.TB_FILEINFO;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface FILEINFORepository extends JpaRepository<TB_FILEINFO, Integer> {

    @Query("SELECT f FROM TB_FILEINFO f WHERE f.CHECKSEQ = :checkseq AND f.bbsseq = :bbsseq")
    List<TB_FILEINFO> findAllByCheckseqAndBbsseq(@Param("checkseq") String checkseq, @Param("bbsseq") int bbsseq);

    @Query("SELECT f FROM TB_FILEINFO f WHERE f.FILEORNM = :fileornm AND f.bbsseq = :bbsseq")
    TB_FILEINFO findBySvnmAndSeq(@Param("fileornm") String fileornm, @Param("bbsseq") int bbsseq);

    List<TB_FILEINFO> findByBbsseq(Integer id);

    @Modifying
    @Transactional
    @Query("DELETE FROM TB_FILEINFO f WHERE f.bbsseq = :bbsseq AND f.CHECKSEQ = :checkseq")
    void deleteByBbsseqAndCheckseq(@Param("bbsseq") Integer bbsseq, @Param("checkseq") String checkseq);

    @Query("SELECT f FROM TB_FILEINFO f WHERE f.bbsseq = :bbsseq AND f.CHECKSEQ = :CHECKSEQ")
    List<TB_FILEINFO> findFilesByBbsseqAndCHECKSEQ(@Param("bbsseq") int bbsseq, @Param("CHECKSEQ") String CHECKSEQ);

    Optional<TB_FILEINFO> findByCHECKSEQAndBbsseqAndFILESVNM(String checkSeq, Integer makseq, String fileName);
}
