package mes.app.PaymentStatus.Service;

import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class SportsNoticeService {
    @Autowired
    SqlRunner sqlRunner;

    // BBS read
    public List<Map<String, Object>> getBBSList(String searchText) {
        MapSqlParameterSource params = new MapSqlParameterSource();

        params.addValue("searchText", "%" + searchText + "%");

        StringBuilder sql = new StringBuilder("""
                SELECT
                    b.*,
                    COALESCE((
                        SELECT JSON_QUERY((
                            SELECT
                                f.FILESEQ AS fileseq,
                                f.FILESIZE AS filesize,
                                f.FILEEXTNS AS fileextns,
                                f.FILEORNM AS fileornm,
                                f.FILEPATH AS filepath,
                                f.FILESVNM AS filesvnm
                            FROM TB_FILEINFO f
                            WHERE b.BBSSEQ = f.BBSSEQ
                            AND f.CHECKSEQ = '01'
                            FOR JSON PATH
                        ))
                    ), '[]') AS fileInfos
                FROM
                    TB_BBSINFO b
                WHERE 1=1
                """);
        // 제목필터
        if (searchText != null && !searchText.isEmpty()) {
            sql.append(" AND b.BBSSUBJECT LIKE :searchText");
        }
        // 정렬 조건 추가
        sql.append(" ORDER BY b.BBSDATE DESC");
        List<Map<String,Object>> items = this.sqlRunner.getRows(sql.toString(),params);
        return items;
    }
    // BBS delete
    public void deleteBBS(int bbsseq) {
        MapSqlParameterSource params = new MapSqlParameterSource();


        String sql = """
                DELETE FROM TB_BBSINFO
                WHERE BBSSEQ = :bbsseq
                """;
        params.addValue("bbsseq", bbsseq);

        int deleteCnt = this.sqlRunner.execute(sql,params);
    }
    // File delete
    public void deleteFile(int bbsseq) {
        MapSqlParameterSource params = new MapSqlParameterSource();


        String sql = """
                DELETE FROM TB_FILEINFO
                WHERE bbsseq = :bbsseq
                AND CHECKSEQ = '01'
                """;
        params.addValue("bbsseq", bbsseq);

        int deleteCnt = this.sqlRunner.execute(sql,params);
    }
}
