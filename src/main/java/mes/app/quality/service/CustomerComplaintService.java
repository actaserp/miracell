package mes.app.quality.service;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.util.StringUtils;
import mes.domain.services.SqlRunner;

/**
 * 고객불만 관리 서비스.
 * customer_complaint 테이블 CRUD. UDI 보고자료 서비스와 동일한 SqlRunner + native SQL 패턴.
 */
@Service
public class CustomerComplaintService {

	@Autowired
	SqlRunner sqlRunner;

	/** 목록 조회 (그리드) — 접수일 범위 + 처리상태 + 키워드(고객사/제품/접수번호) */
	public List<Map<String, Object>> getList(String dateFrom, String dateTo,
											 String actionState, String keyword) {

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("dateFrom", dateFrom);
		p.addValue("dateTo", dateTo);
		p.addValue("actionState", actionState);

		String sql = """
				select c.id
				, c."ComplaintNo"    as complaint_no
				, c."ReceiptDate"    as receipt_date
				, c."Company_id"     as company_id
				, comp."Name"        as company_name
				, c."Material_id"    as material_id
				, m."Code"           as material_code
				, m."Name"           as material_name
				, c."LotNo"          as lot_no
				, c."ComplaintType"  as complaint_type
				, case c."ComplaintType" when '1' then '품질'
				                         when '2' then '포장'
				                         when '3' then '배송'
				                         when '4' then '기타'
				                         else '' end as complaint_type_name
				, c."Content"        as content
				, c."Qty"            as qty
				, c."ActionState"    as action_state
				, case c."ActionState" when '1' then '접수'
				                       when '2' then '처리중'
				                       when '3' then '완료'
				                       else '' end as action_state_name
				, c."ActionContent"  as action_content
				, c."ActionDate"     as action_date
				, c."Person_id"      as person_id
				, per."Name"         as person_name
				, c."Description"    as description
				from customer_complaint c
				left join company  comp on comp.id = c."Company_id"
				left join material m    on m.id    = c."Material_id"
				left join person   per  on per.id  = c."Person_id"
				where 1 = 1
				""";

		if (StringUtils.isEmpty(dateFrom) == false)
			sql += " and c.\"ReceiptDate\" >= cast(:dateFrom as date) ";
		if (StringUtils.isEmpty(dateTo) == false)
			sql += " and c.\"ReceiptDate\" <= cast(:dateTo as date) ";
		if (StringUtils.isEmpty(actionState) == false)
			sql += " and c.\"ActionState\" = :actionState ";
		if (StringUtils.isEmpty(keyword) == false) {
			sql += """
					 and ( comp."Name" ilike concat('%',:keyword,'%')
					    or m."Name"    ilike concat('%',:keyword,'%')
					    or c."ComplaintNo" ilike concat('%',:keyword,'%') )
					""";
			p.addValue("keyword", keyword);
		}

		sql += " order by c.\"ReceiptDate\" desc, c.id desc ";

		return this.sqlRunner.getRows(sql, p);
	}

	/** 단건 조회 */
	public Map<String, Object> getComplaint(Integer id) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("id", id);
		String sql = """
				select c.*
				, comp."Name" as company_name
				, m."Code"    as material_code
				, m."Name"    as material_name
				, per."Name"  as person_name
				from customer_complaint c
				left join company  comp on comp.id = c."Company_id"
				left join material m    on m.id    = c."Material_id"
				left join person   per  on per.id  = c."Person_id"
				where c.id = :id
				""";
		return this.sqlRunner.getRow(sql, p);
	}

	/**
	 * 접수번호 채번: CC-YYYYMMDD-순번(3자리).
	 * 같은 날짜의 마지막 번호 뒤에 붙인다.
	 */
	public String nextComplaintNo(String receiptDate) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("receiptDate", receiptDate);
		String sql = """
				select 'CC-' || to_char(cast(:receiptDate as date),'YYYYMMDD') || '-' ||
				       lpad( (coalesce(max( cast(split_part("ComplaintNo",'-',3) as integer) ),0) + 1)::text, 3, '0') as no
				from customer_complaint
				where "ComplaintNo" like 'CC-' || to_char(cast(:receiptDate as date),'YYYYMMDD') || '-%'
				""";
		Map<String, Object> row = this.sqlRunner.getRow(sql, p);
		return row == null ? null : (String) row.get("no");
	}

	/** 신규 등록 */
	public Integer insert(MapSqlParameterSource p) {
		String sql = """
				insert into customer_complaint (
				  "ComplaintNo","ReceiptDate","Company_id","Material_id","LotNo",
				  "ComplaintType","Content","Qty",
				  "ActionState","ActionContent","ActionDate","Person_id","Description",
				  _status,_created,_creater_id,spjangcd
				) values (
				  :complaintNo, cast(:receiptDate as date), :companyId, :materialId, :lotNo,
				  :complaintType, :content, cast(:qty as numeric),
				  :actionState, :actionContent, cast(:actionDate as date), :personId, :description,
				  'a', now(), :userId, :spjangcd
				)
				returning id
				""";
		Map<String, Object> row = this.sqlRunner.getRow(sql, p);
		return row == null ? null : ((Number) row.get("id")).intValue();
	}

	/** 수정 */
	public void update(MapSqlParameterSource p) {
		String sql = """
				update customer_complaint set
				  "ReceiptDate"   = cast(:receiptDate as date),
				  "Company_id"    = :companyId,
				  "Material_id"   = :materialId,
				  "LotNo"         = :lotNo,
				  "ComplaintType" = :complaintType,
				  "Content"       = :content,
				  "Qty"           = cast(:qty as numeric),
				  "ActionState"   = :actionState,
				  "ActionContent" = :actionContent,
				  "ActionDate"    = cast(:actionDate as date),
				  "Person_id"     = :personId,
				  "Description"   = :description,
				  _modified       = now(),
				  _modifier_id    = :userId
				where id = :id
				""";
		this.sqlRunner.execute(sql, p);
	}

	/** 삭제 */
	public void delete(List<Integer> ids) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("ids", ids);
		String sql = "delete from customer_complaint where id in (:ids)";
		this.sqlRunner.execute(sql, p);
	}
}
